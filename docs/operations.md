# Operations

Keeping a deployed server healthy: the databases' accounts, upgrades, backups, health checks and monitoring.

## MongoDB accounts

A fresh database gets the backend's account from `mongo-init.js` on its first start. MongoDB runs
init scripts only on an empty data directory, so a database created before that account existed
gets it from `scripts/mongo-app-user.sh` instead - which `deploy.sh` and `start.sh` run before the
backend starts, and which is safe to run any number of times. To move an existing server over:

1. Add `MONGO_APP_PASSWORD` to `.env` (`openssl rand -hex 24`); `MONGO_APP_USER` defaults to
   `cpintel_app`. Docker Compose refuses to start the stack until it is set.
2. Optionally first: `scripts/mongo-app-user.sh --check` - reports whether the account exists and
   changes nothing.
3. `./deploy.sh <sha>` as usual. It creates the account (if missing) and checks it before any
   application container is touched; if that fails, nothing else happens.

The script never changes an existing account's roles - an account of that name with other roles
is reported and left for a person to decide - and changes its password only with
`--reset-password`. It writes no data: it signs in as the account, reads its `connectionStatus`,
and confirms the account is refused `admin.system.users`. Run `scripts/mongo-app-user.sh --check`
at any time to see the same. To see which account the running backend actually uses - one line
per client library and account; the backend's names the Java driver, and should read
`... as cpintel_app` (your own mongosh session shows up as root):

```bash
# mongosh signs in with the container's own root credentials, read inside the script, so the
# password is on no command line (as in the scripts here).
printf '%s\n' \
  'db = connect("mongodb://" + encodeURIComponent(process.env.MONGO_INITDB_ROOT_USERNAME) + ":" + encodeURIComponent(process.env.MONGO_INITDB_ROOT_PASSWORD) + "@localhost:27017/admin");' \
  'print([...new Set(db.currentOp({ $all: true }).inprog.filter(o => o.effectiveUsers && o.clientMetadata).map(o => o.clientMetadata.driver.name + " as " + o.effectiveUsers[0].user))])' |
  docker exec -i cpintel-mongo sh -c 'f=$(mktemp); cat > "$f"; mongosh --quiet --nodb --file "$f"; rm -f "$f"'
```

Going back: an older version reads its MongoDB address from the same `docker-compose.yml`, so
`./deploy.sh --rollback` keeps working; to run on the root account again, restore the old
`SPRING_DATA_MONGODB_URI` line and recreate the backend. The extra account harms nothing; remove
it, if wanted, with `db.getSiblingDB('cpintel').dropUser('cpintel_app')` as root.

## Upgrading an existing MongoDB

This is the one procedure for bringing a database created by an earlier release up to the indexes
this backend expects. A fresh installation needs none of it: `mongo-init.js` and the backend's
first start create everything. It is not done by `deploy.sh`.

**The indexes.** The backend declares these, and since `78f1dc6` it creates them at start
(`spring.data.mongodb.auto-index-creation`). Before that, `MongoConfig` built its `MongoTemplate`
with a mapping context of its own that ignored the setting, so the backend created no index at
all; only `mongo-init.js`'s existed, and the two unique ones it does not make were not enforced.

| Collection | Index | Key | Unique | On a fresh install, made by |
|---|---|---|---|---|
| `cf_submissions` | `idx_cf_user_submission` | `userId`, `cfSubmissionId` | yes | `mongo-init.js` |
| `cf_submissions` | `idx_cf_user_verdict` | `userId`, `verdict` | no | `mongo-init.js` |
| `code_submissions` | `idx_code_user_problem` | `userId`, `platform`, `contestId`, `problemIndex`, `submittedAt` desc | no | `mongo-init.js` |
| `code_submissions` | `idx_code_user_external` | `userId`, `platform`, `externalId` | no | `mongo-init.js` |
| `personal_files` | `idx_files_user_name` | `userId`, `name` | yes | the backend |
| `contest_file_rules` | `idx_file_rule_contest` | `platform`, `contestId` | yes | the backend |

(The backend also makes plain indexes - `idx_cf_user_created`, `idx_snap_user_platform` and
single-field ones - that no earlier release had in another form; they need nothing here.)

**What an earlier database can hold instead.** `mongo-init.js` before `874bfc5` made the first
four under MongoDB's default names (`userId_1_cfSubmissionId_1`, `userId_1_verdict_1`,
`userId_1_platform_1_contestId_1_problemIndex_1_submittedAt_-1`,
`userId_1_platform_1_externalId_1`); MongoDB will not hold a second index on the same key, so the
backend stops at start with `IndexOptionsConflict`. The first releases (`6041bf0`, `7487e24`) had
a unique `cfSubmissionId_1` across all users instead. And `personal_files` and
`contest_file_rules` may hold duplicates - two uploads of one name racing each other, two saves of
one rule - which stop the backend at start with a duplicate key error (`E11000`).

**A backend that stops at start is a protective stop, not the migration.** It changes no data,
and `deploy.sh` then returns to the previous version by itself; but neither it nor
`./deploy.sh --rollback` fixes indexes or duplicates. Do the steps below first.

### The procedure

`scripts/mongo-index-upgrade.sh` does the checks and changes. It needs `--container` named
explicitly, works on the `cpintel` database as the container's root account (as
`scripts/backup.sh` does), and never deletes a document, uses `dropIndexes()`, touches `_id_`,
or drops an index it has not matched exactly - key, order, direction and options - against a
known earlier definition. Exit codes: 0 done or ready, 3 duplicates remain, 4 refused (no
backup, or something can still write), 1 anything that needs a person, 2 usage.

1. **Stop everything that writes**, during a window when nobody needs the site: `docker compose
   stop backend` (and anything else you run that signs in to MongoDB). The script refuses to
   change anything while a Java driver or the backend's account (`MONGO_APP_USER`) is connected.
2. **Back up**, now that nothing writes: `./scripts/backup.sh pre-index-upgrade`, which writes
   `postgres-<time>-pre-index-upgrade.dump` and `mongo-<time>-pre-index-upgrade.archive.gz` to
   `CPINTEL_BACKUP_DIR`. Changes need `--backup` naming that MongoDB dump: under a day old
   (`CPINTEL_BACKUP_MAX_AGE_HOURS`), readable by `gzip -t`, and read through by
   `mongorestore --dryRun`.
3. **Check** (read-only; safe at any time, even with the backend running):

   ```bash
   scripts/mongo-index-upgrade.sh --container cpintel-mongo --check
   ```

   It prints, per unique index, how many keys are duplicated - counts only, no names - and per
   index whether it is in place, will be created, will be renamed from an earlier definition, or
   needs a person.
4. **If there are duplicates, export them** (read-only) - a record to review and to go back to:

   ```bash
   scripts/mongo-index-upgrade.sh --container cpintel-mongo --export /var/backups/cpintel/index-upgrade
   ```

   One JSON line per conflicting document, in Extended JSON (types kept), in a new private
   directory: `personal_files.conflicts.jsonl` (without file contents),
   `contest_file_rules.conflicts.jsonl`, `cf_submissions.conflicts.jsonl`.
5. **Resolve them, keeping the data.**
   - *Personal files:* plan, review, then apply. Only the name changes: the first upload keeps
     its name and each later one becomes `name (2).cpp`, `name (3).cpp`, ... - the next number
     free among all of that user's files, so an existing `name (2).cpp` is skipped; files without
     an extension become `Makefile (2)`; names stay within the 120-character limit. Content,
     owner and dates stay as they were, and the plan (`_id`, user, old and new name) is written
     to disk before anything is renamed.

     ```bash
     scripts/mongo-index-upgrade.sh --container cpintel-mongo --rename-duplicate-files /var/backups/cpintel/index-upgrade
     scripts/mongo-index-upgrade.sh --container cpintel-mongo --rename-duplicate-files /var/backups/cpintel/index-upgrade \
       --confirm --backup /var/backups/cpintel/mongo-<time>-pre-index-upgrade.archive.gz
     ```

   - *Contest file rules:* a person decides. Each pair is an admin's decision about one contest
     (or, with no contest, about all of them); read both in the export, keep the one that is
     right, and remove the other by its `_id`, as root:
     `db.contest_file_rules.deleteOne({ _id: ObjectId('<_id from the export>') })`. The export is
     the record of what was removed; to put one back, `mongoimport` that line into
     `cpintel.contest_file_rules` as root (it reads Extended JSON, so the `_id` comes back as it
     was).
   - *Codeforces submissions:* every earlier release kept these unique, so duplicates mean the
     database never had its indexes. They are copies of what Codeforces holds; keep one of each
     pair the same way, or remove the user's rows and let a resync bring them back.

6. **Check again** until it reports no duplicates (exit 0).
7. **Apply:**

   ```bash
   scripts/mongo-index-upgrade.sh --container cpintel-mongo --apply \
     --backup /var/backups/cpintel/mongo-<time>-pre-index-upgrade.archive.gz
   ```

   Missing indexes are created under their names. Each earlier default-named one is dropped and
   recreated under its name; for `userId_1_cfSubmissionId_1` a temporary unique index
   (`p7_guard_cf_user_submission`, the same fields reversed) holds the uniqueness meanwhile,
   because MongoDB will not hold two indexes on one key. The first releases' global
   `cfSubmissionId_1` goes once the per-user one exists. It ends by checking every index is in
   place.
8. **Deploy and start the backend** (`./deploy.sh <sha>`); it finds its indexes in place. Then
   check the site: sign in, open personal files, open a practice plan.

**Running it again** is always safe: every step reads the state first, so after a completed run
it changes nothing, and after an interrupted one (including one that stopped between the guard
and the new index) it carries on from where it stopped. Renames are guarded by the old name, so
none is applied twice.

**When it stops (exit 1)** it says which index and why - most often an index of a wanted name
with another definition, or one of another name on the same key that no release made. Nothing
further was changed; compare it with the table above and drop or rename that index by hand,
then run the check again.

**Going back.** The previous backend version runs on the renamed indexes and on renamed files
(it creates no indexes), so `./deploy.sh --rollback` keeps working. Renames are not undone by
it: the plan file has every old name. To return the database exactly as it was, restore the
backups from step 2 - `docker compose stop backend && ./scripts/restore.sh <time>-pre-index-upgrade`
puts back both databases, and so discards anything written to either since.

The integration test rehearses all of this on a throwaway copy of an earlier release's database
([Full-stack integration test](testing.md#full-stack-integration-test-opt-in-locally-on-main-in-ci)), and
`scripts/test_mongo_index_upgrade.py` covers each case of the script on its own.

## Backups and restore

`scripts/backup.sh` (nightly from cron, and by `deploy.sh` before each deploy) dumps the `cpintel`
database of Postgres (`pg_dump -Fc`) and of MongoDB (`mongodump --gzip`), data and index
definitions, from inside their containers with the credentials those containers already hold.
Each dump is read back (`pg_restore --list`, `gzip -t`) before it is kept; a run that fails keeps
nothing, so a stamp is always a pair: `postgres-<stamp>.dump` and `mongo-<stamp>.archive.gz`,
mode 0600, in `CPINTEL_BACKUP_DIR` (0700, default `/var/backups/cpintel`), 14 days locally, and
copied to `CPINTEL_BACKUP_REMOTE` if set.

**Not in a backup:** database accounts and roles - MongoDB's root and the backend's
`cpintel_app`, Postgres's roles - which come from `.env`, `mongo-init.js` and
`scripts/mongo-app-user.sh`; `.env` and its keys (keep a copy off the server: without the
sealing keys, stored examination passwords and DOMjudge logins cannot be read); Redis (sign-in
sessions and exam grants - people sign in again, and grants are in Postgres too); anything
outside the two databases.

**The drill** - proves a backup reads back without touching live data:
`./scripts/restore.sh --test <stamp>` restores both into scratch databases in the same containers,
prints row and document counts and the schema version, and drops them again.

**A real restore** replaces the `cpintel` database in both containers with the backup:

1. `docker compose stop backend` - `restore.sh` refuses while it runs.
2. `./scripts/restore.sh <stamp>` - it reads both archives back before changing anything, names
   the containers it is about to overwrite, and asks you to type `restore`.
3. `scripts/mongo-app-user.sh --check` (and without `--check` if the account is missing - a new
   server, for one): accounts are not in the backup.
4. `scripts/mongo-index-upgrade.sh --container cpintel-mongo --check`: a restore brings back the
   indexes the backup was taken with, so a backup from before the index upgrade needs
   [Upgrading an existing MongoDB](#upgrading-an-existing-mongodb) again.
5. `docker compose start backend`.

Everything written since the backup is lost, in both databases. `scripts/test_backup_restore.py`
runs all of this - backup, the drill, a restore into another pair of throwaway containers, the
account, a backup from before the index upgrade, and the refusals - on throwaway databases only;
no backup of a real server has been restored by it.

## Health checks

`/actuator/health` is what the backend container's own Docker check and `deploy.sh` ask, and what
nginx serves at `https://<host>/actuator/health` (nginx's own container check is its `/health`).
It is UP when Postgres, MongoDB and Redis all answer, and DOWN otherwise, within about
5 seconds: MongoDB's client gives up looking for a server after 5 s
(`cpintel.mongo.server-selection-timeout`), the database check answers DOWN after 5 s
(`cpintel.health.timeout`), and Redis after its 2 s command timeout. (Before this, a stopped
MongoDB or Postgres held every health answer for 30 s.) It is UP again as soon as the datastore is
back. DOWN marks the container unhealthy; Docker does not restart it, so a brief outage of a
datastore ends without one. An anonymous caller sees only `{"status": ...}`; the components and
their errors are shown to a signed-in super admin. The integration test stops each datastore in
turn and checks all of this.

## Monitoring (optional)

```bash
docker compose --profile monitoring up -d
```

Grafana on `localhost:3000`, Prometheus on `localhost:9090` — loopback only; on a server,
reach them over an SSH tunnel.

**Upgrading a monitoring stack that already has data.** `deploy.sh` never starts this profile,
so the next `--profile monitoring up` is the upgrade. Grafana 12.4 migrates its database on first
start and Prometheus 3 writes its newer storage format; neither project supports running the
older version on what the newer one wrote. (In a rehearsal on scratch volumes with a few minutes'
data, 10.4 and 2.51 did start again on the upgraded volumes — not something to count on with
real data.) The way back is a copy taken before. Loki (profile `logs`) keeps the TSDB v13 schema
it has always used. Copy the volumes with the services stopped (a running Grafana or Prometheus
can be mid-write); this takes the dashboards down for under a minute and touches nothing else:

```bash
docker compose --profile monitoring stop grafana prometheus alertmanager
B=/var/backups/cpintel/monitoring-$(date -u +%Y%m%dT%H%M%SZ) && mkdir -p "$B"
for v in grafana_data prometheus_data alertmanager_data; do
  docker run --rm -v cpintel_$v:/data:ro -v "$B":/backup alpine:3.20 tar -C /data -czf /backup/$v.tar.gz .
done
(cd "$B" && for f in *.tar.gz; do tar -tzf "$f" >/dev/null && echo "ok $f"; done && sha256sum *.tar.gz > SHA256SUMS)
docker compose --profile monitoring up -d
```

To prove a copy restores, unpack it into a scratch volume and start the old image on it, never on
the live volume:

```bash
docker volume create grafana_restore_test
docker run --rm -v grafana_restore_test:/data -v "$B":/backup:ro alpine:3.20 tar -C /data -xzf /backup/grafana_data.tar.gz
docker run -d --name grafana-restore-test -p 127.0.0.1:13000:3000 -v grafana_restore_test:/var/lib/grafana grafana/grafana:10.4.0
curl -s 127.0.0.1:13000/api/health   # "database": "ok"
docker rm -f grafana-restore-test && docker volume rm grafana_restore_test
```

To go back after an upgrade, put the copy back over the live volume (this replaces what is there)
and start the version that wrote it (`grafana/grafana:10.4.0`, `prom/prometheus:v2.51.0`,
`prom/alertmanager:v0.27.0`, the tags `docker-compose.yml` had before the upgrade), for example:

```bash
docker compose --profile monitoring stop grafana
docker run --rm -v cpintel_grafana_data:/data -v "$B":/backup:ro alpine:3.20 \
  sh -c 'find /data -mindepth 1 -delete && tar -C /data -xzf /backup/grafana_data.tar.gz'
# then set the old image tag for grafana in docker-compose.yml and:
docker compose --profile monitoring up -d grafana
```

Prometheus keeps 7 days of metrics, so starting the old version on an empty volume is also an
acceptable way back. All of the above was rehearsed on scratch volumes, not on a live server.
