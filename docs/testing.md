# Tests and CI

## Running the tests

Three layers, from cheap to thorough. Only the first runs on every push and pull request:

| Layer | What | Where it runs |
|---|---|---|
| Unit, component and script tests | the commands below; no running stack | CI and `scripts/ci-local.sh`, every push |
| Full-stack integration test | the production images under a throwaway Compose project: fresh install, upgrade, API workflow through nginx | `scripts/integration-test.sh`; CI on `main` only |
| Browser smoke test | a real Chrome through the main journeys | `scripts/e2e-smoke.sh`, by hand |

```bash
cd backend  && ./mvnw verify    # unit, web-slice and Postgres/Mongo container tests, then the JaCoCo coverage gate
cd electron && npm test         # away-time accounting, external-link schemes, executable sizes
python3 -I scripts/test_grab_cf_cookie.py   # where the Codeforces helper will send a session
python3 -I scripts/test_nginx_edge.py       # edge headers and the restarting answer (uses Docker)
python3 -I scripts/test_mongo_app_user.py   # the backend's MongoDB account, fresh and existing (uses Docker)
python3 -I scripts/test_mongo_index_upgrade.py  # scripts/mongo-index-upgrade.sh, case by case (uses Docker)
python3 -I scripts/loadtest/test_stack.py   # the load test's settings file and MongoDB start
python3 -I scripts/integration/test_isolation.py  # the integration test's isolation check
python3 -I scripts/integration/test_cleanup_lock.py  # its cleanup leaves a running test alone (Docker, flock)
python3 -I scripts/test_compose_secrets.py      # no secret on a command line; Redis as Compose starts it (Docker)
python3 -I scripts/test_backup_restore.py       # backup.sh and restore.sh on throwaway databases (Docker)
python3 -I scripts/test_deploy_preflight.py     # the deployment preflight, on made-up settings
cd frontend && npm run type-check && npm run lint
cd frontend && npm test         # session tests (Node), then component and page tests (Vitest, jsdom)
```

All of these run in CI (`.github/workflows/ci.yml`) and in `scripts/ci-local.sh`. The backend
suite is the large one, several hundred tests; Maven prints the exact count at the end of a run.
The backend tests need no database, Redis or network of their own, with one exception:
`PostgresSchemaTest` starts a throwaway `postgres:16-alpine` through Testcontainers, applies every
Flyway migration, validates the entity mappings against the result and runs each native query.
`MongoLeastPrivilegeTest` does the same with `mongo:7.0` and the repository's `mongo-init.js`,
and runs every Mongo repository, GridFS and index creation as the backend's own account. Both
are skipped on a machine without Docker, except under CI (`CI` set), where a missing Docker
fails the run. The coverage gate (`jacoco.min.instruction` in `backend/pom.xml`) only ever moves
up. The nginx test skips its container half when Docker is unavailable or `CPINTEL_NO_DOCKER=1`
is set.

`npm test` in `frontend/` runs two suites, neither needing a browser or the backend:
`tests/session.test.mjs` drives the axios session handling against a scripted server on Node's
own runner, and Vitest runs the component and page tests under `src/**/*.test.tsx` - route guards
and roles, signing out (including a profile answer that arrives after it), the practice plan's
states and failures, sign-in, examination unlock and keyboard use. Those use the real routes,
providers and API client, with the backend scripted behind the client (`src/test/server.ts`).

### Full-stack integration test (opt-in locally, on `main` in CI)

```bash
scripts/integration-test.sh                  # fresh install, then upgrade; removes everything it made
scripts/integration-test.sh --only fresh     # or --only upgrade
scripts/integration-test.sh --keep           # leave the last stack up to look at
scripts/integration-test.sh --cleanup        # list what an interrupted run left (--yes removes it)
scripts/integration-test.sh --cleanup --yes --project cpintel-it-<id>   # only that one
```

It builds the backend, frontend and nginx images from the checkout and runs them with Postgres,
MongoDB and Redis under a Compose project of its own (`cpintel-it-<run id>`), with secrets
generated for the run. Needs Docker with Compose 2.24 or later and `python3`; a first run takes
several minutes for the image builds, later ones reuse the build cache.

- **Fresh install:** the backend's MongoDB account is checked (readWrite on `cpintel` only);
  then, through nginx over https: the app's page, health (UP, with no details for an anonymous
  caller), a 401 for an anonymous or wrongly-passworded request, the bootstrap admin's sign-in
  and refresh cookie flags, the profile, a personal file written to MongoDB and read back, a
  practice plan, a password reset request, and sign-out refusing both the token and the refresh
  cookie. Then MongoDB, Redis and Postgres are stopped in turn: health must report DOWN within
  20 s, every answer within 10 s (Docker's own check timeout) and with no details for an
  anonymous caller, and UP again once each is back. Measured here: 5 s, 2 s and 5 s.
- **Upgrade:** MongoDB starts from the previous release's `mongo-init.js`
  (`scripts/integration/fixtures/`) with data written as root, including duplicate personal file
  names (with extensions and an existing `(2)`) and a duplicated contest rule. Then
  [the procedure](operations.md#upgrading-an-existing-mongodb): `scripts/mongo-app-user.sh` creates the
  account and finds nothing to do the second time; the backend, started on the old indexes, must
  stop; the index upgrade must find every conflict without changing anything, refuse without a
  backup (one is made with `scripts/backup.sh`) and while duplicates remain, export them without
  file contents, rename the duplicate files, and - after the test removes one rule as a person
  would - apply, and change nothing on a second run. Every personal file must keep its content and
  metadata under a name of its own, the backend must come up, and the API must work.
- **Both:** Compose must refuse a configuration without `MONGO_APP_PASSWORD`, no generated
  secret or reset link may appear in any container's log, and none may appear on any container's
  command line, process list or health check.

**What it never touches.** Before anything starts, `scripts/integration/isolation.py` checks the
resolved configuration: the project is `cpintel-it-*`, every container, volume and network
carries its name, bind mounts are read-only files from the checkout, and nothing is published
but nginx on a free `127.0.0.1` port. The development stack (`cpintel` project, `cpintel_*`
volumes) is never named. Cleanup removes only the run's own projects - their containers,
volumes, networks and the images Compose built for them - and the run fails if the volume list
afterwards differs from before. It never prunes. A run holds a lock (`flock` on
`$XDG_RUNTIME_DIR/cpintel-it-locks/<run>.lock`, or under `/tmp`) for as long as it lives, and
`--cleanup --yes` skips any project whose run still holds it, so it cannot pull a stack from under
a test that is still going. The runner, monitoring and certbot are not started.

**When it fails.** It prints which step, the containers' state and the last log lines, with
every generated secret replaced by `<redacted>`. Common causes: a port or disk space problem
during the image build (the build log is in the run's temporary directory, removed afterwards -
rerun with `--keep` to keep the stack); Docker Compose older than 2.24; or a backend that does
not reach UP within five minutes on a slow machine.

### Browser smoke test (opt-in)

Not part of `npm test` or CI: it starts a throwaway stack and drives a real Chrome through sign-in,
a reload, a wrong password, the dashboard, roadmap and practice plan (and a server error on it,
shown rather than masked, then retried), an administrator's console and a phone-width layout
check.

```bash
cd frontend && npm run test:e2e:smoke    # or scripts/e2e-smoke.sh [--keep] [--rebuild]
scripts/e2e-smoke.sh --cleanup           # lists anything an interrupted run left (--yes removes it)
```

It needs Docker, Java 21, Node 24 and a Chrome or Chromium (`E2E_CHROME` if it is not on PATH).
Its containers are `--rm`, labelled and named `cpintel-e2e-*`, on free loopback ports and with no
named volume, so the development stack and the `cpintel_*` volumes are never touched; passwords
are generated per run and deleted with its temporary directory, and Codeforces is stubbed.

### Release readiness

What the tests above do not cover, and what a release still needs - none of it is done by any
script here:

- **Secrets:** a production `.env` with every key from `.env.example`, including
  `MONGO_APP_PASSWORD` (Compose refuses to start without it) and the sealing keys, with copies
  kept off the server.
- **Upgrading an existing server:** [MongoDB accounts](operations.md#mongodb-accounts), then
  [Upgrading an existing MongoDB](operations.md#upgrading-an-existing-mongodb) - in a maintenance window, with
  the backend stopped, before `./deploy.sh`. The integration test rehearses it on a throwaway
  database; it has not been run on a real one.
- **Mail:** `SMTP_HOST`, or a decision to run without it. Reset links are not logged unless
  `CPINTEL_MAIL_LOG_UNDELIVERED=true`; off, a reset reaches nobody.
- **The preflight:** `python3 -I scripts/deploy-preflight.py --version <sha>` with no FAIL.
- **Backups and a rollback you have tried:** [Backups and restore](operations.md#backups-and-restore) - the
  drill on the real server, an off-server copy, and `./deploy.sh --rollback` there. Tested here
  only on throwaway databases.
- **Monitoring** if you want alerts ([Monitoring](operations.md#monitoring-optional)).
- **The dependency scan's exceptions** expire on 2026-12-31 (below): before then, Spring Boot 4.

Known and still open - none of these is fixed by anything above:

- Secrets are in the containers' environments, which `docker inspect` shows to root and the
  `docker` group on the host. (No longer on any command line, and the bootstrap admin's address
  is no longer logged.)
- The deploy-time image is not re-scanned on the server; the vulnerability gate runs in CI on
  the jar it builds.
- Hikari still waits up to 30 s for a pooled connection, so with Postgres down a request (not
  the health check) can take that long to fail.
- The `Full-stack integration` CI job has run only on a developer machine, not on a GitHub
  runner, and does not yet gate the image builds.
- No deployment of this release has been made; everything above was verified on test stacks.

Passing tests mean the code behaves as tested on a test stack. They do not certify a server,
its data, its secrets or its network.

### Running CI before pushing

`scripts/ci-local.sh` runs the same stages as `.github/workflows/ci.yml` — workflow lint, backend
tests with the coverage gate, frontend type check, tests and build, the desktop and script tests, and
the Docker images — against a
clean snapshot of the repo, so local `node_modules`, `target/` or `public/mathjax` cannot hide a
failure the CI runner would hit. It takes about two minutes warm.

```bash
./scripts/ci-local.sh               # working tree, uncommitted changes included
./scripts/ci-local.sh --no-docker   # skip the image builds and the nginx container test
./scripts/ci-local.sh --no-cache    # cold Docker build, as a fresh runner does
./scripts/ci-local.sh --audit       # add the dependency audits (backend scan needs trivy installed)

git config core.hooksPath .githooks # run it on every git push (skip once: git push --no-verify)
```

### Dependency vulnerabilities

The backend's libraries are scanned in CI by the `Backend dependency scan` job, which the image
builds wait for: `scripts/scan-backend-deps.sh` runs Trivy (a pinned, checksum-verified release)
over the built jar, so it checks exactly the versions that ship. It needs no key or account, and
caches its vulnerability databases between runs.

- **Fails on** HIGH or CRITICAL findings that have a fixed version. Findings with no fix yet, and
  every lower severity, are printed and uploaded as `backend-vulnerabilities` but do not fail.
- **Proves it looked:** it first scans `.github/security/canary` (log4j-core 2.14.1) and fails
  unless it finds CVE-2021-44228, so a database that did not load cannot pass silently.
- **When the databases cannot be downloaded,** a cached copy up to 7 days old is used, with a
  warning in the run; with none, the job fails.
- **Accepting a finding:** add it to `backend/.trivyignore.yaml` with a `statement` saying why and
  an `expired_at` date, after which it fails again. It currently holds three Spring Framework
  6.2.19 CVEs fixed only in 7.0.9 (Spring Boot 4), none of which reaches code the backend uses;
  they expire on 2026-12-31. Do not extend that date to make the scan pass: from 2027-01-01 the
  job fails until the backend is on Spring Boot 4 (4.1 is supported until 2027-07-31), or the
  entries are re-checked against the code and renewed with a reason.

The OWASP dependency-check step in the `Dependency vulnerabilities` job is a second opinion; it
runs only with an `NVD_API_KEY` repository secret (free from NVD) and does not block. The
frontend is checked by `npm audit` in the same job.
