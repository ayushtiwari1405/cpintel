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
# A real restore replaces everything written since the backup was taken. Stop the backend first
# (docker compose stop backend) so nothing writes while the data is being put back.
set -euo pipefail

DIR="${CPINTEL_BACKUP_DIR:-/var/backups/cpintel}"
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

  docker exec cpintel-postgres sh -c "dropdb -U \"\$POSTGRES_USER\" --if-exists $SCRATCH \
    && createdb -U \"\$POSTGRES_USER\" $SCRATCH"
  docker exec -i cpintel-postgres sh -c "pg_restore -U \"\$POSTGRES_USER\" -d $SCRATCH \
    --no-owner --exit-on-error" < "$PG"
  docker exec cpintel-postgres sh -c "psql -U \"\$POSTGRES_USER\" -d $SCRATCH -At -c \"
    select 'postgres users: ' || count(*) from users
    union all select 'postgres examinations: ' || count(*) from group_contests
    union all (select 'postgres schema version: ' || version from flyway_schema_history
               where success order by installed_rank desc limit 1)\""

  docker exec -i cpintel-mongo sh -c "mongorestore --quiet --archive --gzip --drop \
    --nsFrom 'cpintel.*' --nsTo '$SCRATCH.*' \
    -u \"\$MONGO_INITDB_ROOT_USERNAME\" -p \"\$MONGO_INITDB_ROOT_PASSWORD\" \
    --authenticationDatabase admin" < "$MONGO"
  docker exec cpintel-mongo sh -c "mongosh --quiet \
    -u \"\$MONGO_INITDB_ROOT_USERNAME\" -p \"\$MONGO_INITDB_ROOT_PASSWORD\" \
    --authenticationDatabase admin --eval '
      const d = db.getSiblingDB(\"$SCRATCH\");
      d.getCollectionNames().sort().forEach(c =>
        print(\"mongo \" + c + \": \" + d[c].countDocuments({})));
      d.dropDatabase();'"

  docker exec cpintel-postgres sh -c "dropdb -U \"\$POSTGRES_USER\" $SCRATCH"
  echo "Test restore succeeded; scratch databases dropped. Write the date down."
  exit 0
fi

echo "This REPLACES the live CPIntel databases with the backup $STAMP."
echo "Everything written since that backup will be lost."
read -r -p "Type 'restore' to continue: " answer
[ "$answer" = "restore" ] || { echo "Cancelled."; exit 1; }

docker exec -i cpintel-postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d cpintel \
  --clean --if-exists --no-owner --exit-on-error' < "$PG"
docker exec -i cpintel-mongo sh -c 'mongorestore --quiet --archive --gzip --drop \
  -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" \
  --authenticationDatabase admin' < "$MONGO"
echo "Restored $STAMP. Start the backend again: docker compose start backend"
