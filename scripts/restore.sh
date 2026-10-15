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

DIR="${CPINTEL_BACKUP_DIR:-/var/backups/cpintel}"
# The containers, by name; set only to point at another stack, as the restore drill does.
PG_CONTAINER="${CPINTEL_PG_CONTAINER:-cpintel-postgres}"
MONGO_CONTAINER="${CPINTEL_MONGO_CONTAINER:-cpintel-mongo}"
TEST=0
if [ "${1:-}" = "--test" ]; then TEST=1; shift; fi
STAMP="${1:?usage: restore.sh [--test] <stamp>   (see ls $DIR)}"

PG="$DIR/postgres-$STAMP.dump"
MONGO="$DIR/mongo-$STAMP.archive.gz"
for f in "$PG" "$MONGO"; do
  [ -s "$f" ] || { echo "Missing or empty: $f"; exit 1; }
done

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

  docker exec -i "$MONGO_CONTAINER" sh -c "mongorestore --quiet --archive --gzip --drop \
    --nsFrom 'cpintel.*' --nsTo '$SCRATCH.*' \
    -u \"\$MONGO_INITDB_ROOT_USERNAME\" -p \"\$MONGO_INITDB_ROOT_PASSWORD\" \
    --authenticationDatabase admin" < "$MONGO"
  docker exec "$MONGO_CONTAINER" sh -c "mongosh --quiet \
    -u \"\$MONGO_INITDB_ROOT_USERNAME\" -p \"\$MONGO_INITDB_ROOT_PASSWORD\" \
    --authenticationDatabase admin --eval '
      const d = db.getSiblingDB(\"$SCRATCH\");
      d.getCollectionNames().sort().forEach(c =>
        print(\"mongo \" + c + \": \" + d[c].countDocuments({})));
      d.dropDatabase();'"

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

echo "This REPLACES the live CPIntel databases with the backup $STAMP."
echo "Everything written since that backup will be lost."
read -r -p "Type 'restore' to continue: " answer
[ "$answer" = "restore" ] || { echo "Cancelled."; exit 1; }

docker exec -i "$PG_CONTAINER" sh -c 'pg_restore -U "$POSTGRES_USER" -d cpintel \
  --clean --if-exists --no-owner --exit-on-error' < "$PG"
docker exec -i "$MONGO_CONTAINER" sh -c 'mongorestore --quiet --archive --gzip --drop \
  -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" \
  --authenticationDatabase admin' < "$MONGO"
echo "Restored $STAMP. Start the backend again: docker compose start backend"
