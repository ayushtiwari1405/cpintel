#!/usr/bin/env bash
# Restores a backup made by backup.sh.
#
#   ./scripts/restore.sh --test <stamp>   # restore into scratch databases, count, drop them
#   ./scripts/restore.sh <stamp>          # replace the live databases (asks first)
#
# <stamp> is the part of the file name between "postgres-" and ".dump", e.g.
# 20260924T020000Z-nightly. Files are read from CPINTEL_BACKUP_DIR (default
# /var/backups/cpintel).
#
# --test is the drill: it proves a backup can actually be read back without touching live data.
# Run it once after setting backups up, and again whenever the database version changes.
#
# Rehearsed in full on 15 Oct 2026 (Postgres 16, Mongo 7.0, schema V25) against a throwaway pair
# of containers (CPINTEL_PG_CONTAINER / CPINTEL_MONGO_CONTAINER): a live backup, the --test drill,
# then data deleted, restored in place, every count and Mongo index back, and the backend started
# on the result, validated all migrations and signed a user in.
#
# A real restore replaces everything written since the backup was taken. Stop the backend first
# (docker compose stop backend) so nothing writes while the data is being put back.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -f "$ROOT/.env" ]; then
  # CPINTEL_BACKUP_DIR as backup.sh reads it, so both look in the same place.
  while IFS='=' read -r key value; do
    [ "$key" = CPINTEL_BACKUP_DIR ] && [ -z "${CPINTEL_BACKUP_DIR:-}" ] && export "$key=$value"
  done < <(grep -E '^CPINTEL_BACKUP_DIR=' "$ROOT/.env" || true)
fi
DIR="${CPINTEL_BACKUP_DIR:-/var/backups/cpintel}"
# The containers, by name; set only to point at another stack, as the restore drill does.
PG_CONTAINER="${CPINTEL_PG_CONTAINER:-cpintel-postgres}"
MONGO_CONTAINER="${CPINTEL_MONGO_CONTAINER:-cpintel-mongo}"
# For mongodump and mongorestore, which cannot read a script: the root password in a private file
# in the container, given as --config and removed on exit, rather than as -p on a command line.
read -r -d '' MONGO_TOOLS_AUTH <<'SH' || true
umask 077
c=$(mktemp)
trap 'rm -f "$c"' EXIT
printf 'password: "%s"\n' "$(printf %s "$MONGO_INITDB_ROOT_PASSWORD" | sed 's/[\\"]/\\&/g')" > "$c"
SH
# mongosh reads them from the container's environment inside the script it runs, for the same reason.
MONGOSH_ROOT='db = connect("mongodb://" + encodeURIComponent(process.env.MONGO_INITDB_ROOT_USERNAME) + ":" + encodeURIComponent(process.env.MONGO_INITDB_ROOT_PASSWORD) + "@localhost:27017/admin?authSource=admin");'
TEST=0
if [ "${1:-}" = "--test" ]; then TEST=1; shift; fi
STAMP="${1:?usage: restore.sh [--test] <stamp>   (see ls $DIR)}"

PG="$DIR/postgres-$STAMP.dump"
MONGO="$DIR/mongo-$STAMP.archive.gz"
for f in "$PG" "$MONGO"; do
  [ -s "$f" ] || { echo "Missing or empty: $f"; exit 1; }
done
# Both read back before anything is touched: a truncated archive found halfway would leave one
# database restored and the other not (or, with --test, scratch databases behind).
gzip -t "$MONGO" 2>/dev/null || { echo "!!! $MONGO is not a readable archive. Nothing was changed."; exit 1; }
docker exec -i "$PG_CONTAINER" pg_restore --list < "$PG" > /dev/null 2>&1 \
  || { echo "!!! $PG does not read as a Postgres dump. Nothing was changed."; exit 1; }

if [ "$TEST" = 1 ]; then
  SCRATCH="cpintel_restore_test"
  echo "Test restore of $STAMP into scratch databases ($SCRATCH)…"

  docker exec "$PG_CONTAINER" sh -c "dropdb -U \"\$POSTGRES_USER\" --if-exists $SCRATCH \
    && createdb -U \"\$POSTGRES_USER\" $SCRATCH"
  docker exec -i "$PG_CONTAINER" sh -c "pg_restore -U \"\$POSTGRES_USER\" -d $SCRATCH \
    --no-owner --exit-on-error" < "$PG"
  docker exec "$PG_CONTAINER" sh -c "psql -U \"\$POSTGRES_USER\" -d $SCRATCH -At -c \"
    select 'postgres users: ' || count(*) from users
    union all select 'postgres examinations: ' || count(*) from group_contests
    union all (select 'postgres schema version: ' || version from flyway_schema_history
               where success order by installed_rank desc limit 1)\""

  docker exec -i "$MONGO_CONTAINER" sh -c "$MONGO_TOOLS_AUTH
    mongorestore --quiet --archive --gzip --drop --nsFrom 'cpintel.*' --nsTo '$SCRATCH.*' \
      -u \"\$MONGO_INITDB_ROOT_USERNAME\" --config \"\$c\" --authenticationDatabase admin" < "$MONGO"
  printf '%s\n' "$MONGOSH_ROOT" "
      const d = db.getSiblingDB('$SCRATCH');
      d.getCollectionNames().sort().forEach(c =>
        print('mongo ' + c + ': ' + d[c].countDocuments({})));
      d.dropDatabase();" | docker exec -i "$MONGO_CONTAINER" sh -c 'umask 077; f=$(mktemp); cat > "$f"; \
    mongosh --quiet --nodb --file "$f"; s=$?; rm -f "$f"; exit $s'

  docker exec "$PG_CONTAINER" sh -c "dropdb -U \"\$POSTGRES_USER\" $SCRATCH"
  echo "Test restore succeeded; scratch databases dropped. Write the date down."
  exit 0
fi

# Refused while the backend runs: its writes would interleave with the restore, and what came out
# would be neither the backup nor the live data.
BACKEND_CONTAINER="${CPINTEL_BACKEND_CONTAINER:-cpintel-backend}"
if docker ps --format '{{.Names}}' | grep -qx "$BACKEND_CONTAINER"; then
  echo "$BACKEND_CONTAINER is running. Stop it first: docker compose stop backend"
  exit 1
fi

project_of() { docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$1" 2>/dev/null || true; }
echo "This REPLACES the cpintel databases in these containers with the backup $STAMP:"
echo "  Postgres: $PG_CONTAINER (Compose project: $(project_of "$PG_CONTAINER"))"
echo "  MongoDB:  $MONGO_CONTAINER (Compose project: $(project_of "$MONGO_CONTAINER"))"
echo "Everything written to them since that backup will be lost."
read -r -p "Type 'restore' to continue: " answer
[ "$answer" = "restore" ] || { echo "Cancelled."; exit 1; }

docker exec -i "$PG_CONTAINER" sh -c 'pg_restore -U "$POSTGRES_USER" -d cpintel \
  --clean --if-exists --no-owner --exit-on-error' < "$PG"
docker exec -i "$MONGO_CONTAINER" sh -c "$MONGO_TOOLS_AUTH"'
  mongorestore --quiet --archive --gzip --drop \
    -u "$MONGO_INITDB_ROOT_USERNAME" --config "$c" --authenticationDatabase admin' < "$MONGO"
echo "Restored $STAMP. Before starting the backend:"
echo "  - scripts/mongo-app-user.sh --check: database accounts are not in a backup (README, MongoDB accounts)"
echo "  - scripts/mongo-index-upgrade.sh --container $MONGO_CONTAINER --check: a backup brings back the"
echo "    indexes it was taken with, so one from before the index upgrade needs it again"
echo "    (README, Upgrading an existing MongoDB)"
echo "Then: docker compose start backend"
