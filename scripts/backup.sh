#!/usr/bin/env bash
# Dumps both databases, keeps two weeks of them, and copies them off the server.
#
#   ./scripts/backup.sh               # a nightly backup (run from cron — see below)
#   ./scripts/backup.sh pre-deploy    # tagged, so it is easy to find next to a deploy
#
# Postgres holds accounts, examinations and their logs; Mongo holds submitted code and personal
# files. Both are dumped from inside their running containers, with the credentials those
# containers already have, so nothing here needs a password of its own.
#
# Settings (environment, or .env beside docker-compose.yml):
#   CPINTEL_BACKUP_DIR        where dumps are written       default /var/backups/cpintel
#   CPINTEL_BACKUP_KEEP_DAYS  how long local dumps are kept  default 14
#   CPINTEL_BACKUP_REMOTE     rsync target off this server, e.g. backup@nas:/srv/cpintel
#                             (key-based ssh). Unset means local only, which a dead disk loses.
#
# Nightly, as root on the server:
#   0 2 * * * /opt/cpintel/scripts/backup.sh >> /var/log/cpintel-backup.log 2>&1
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -f "$ROOT/.env" ]; then
  # Only the backup settings, and without letting .env override what the caller exported.
  while IFS='=' read -r key value; do
    case "$key" in
      CPINTEL_BACKUP_*) [ -z "${!key:-}" ] && export "$key=$value" ;;
    esac
  done < <(grep -E '^CPINTEL_BACKUP_[A-Z_]+=' "$ROOT/.env" || true)
fi

DIR="${CPINTEL_BACKUP_DIR:-/var/backups/cpintel}"
KEEP="${CPINTEL_BACKUP_KEEP_DAYS:-14}"
TAG="${1:-nightly}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)-$TAG"

mkdir -p "$DIR"
chmod 700 "$DIR"

echo "[$(date -u +%FT%TZ)] backup $STAMP → $DIR"

# Written under a temporary name and renamed once complete, so a dump cut short by a full disk
# or a killed container never sits in the directory looking like a good one.
pg_out="$DIR/postgres-$STAMP.dump"
docker exec cpintel-postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d cpintel -Fc' \
  > "$pg_out.partial"
mv "$pg_out.partial" "$pg_out"

mongo_out="$DIR/mongo-$STAMP.archive.gz"
docker exec cpintel-mongo sh -c 'mongodump --quiet --archive --gzip --db cpintel \
    -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" \
    --authenticationDatabase admin' > "$mongo_out.partial"
mv "$mongo_out.partial" "$mongo_out"

chmod 600 "$pg_out" "$mongo_out"
echo "  postgres $(du -h "$pg_out" | cut -f1)   mongo $(du -h "$mongo_out" | cut -f1)"

# Local retention. Off-server copies are the remote's to keep; see its own retention.
find "$DIR" -maxdepth 1 -type f \( -name 'postgres-*.dump' -o -name 'mongo-*.archive.gz' \) \
  -mtime +"$KEEP" -print -delete | sed 's/^/  pruned /'

if [ -n "${CPINTEL_BACKUP_REMOTE:-}" ]; then
  rsync -a --chmod=F600 "$pg_out" "$mongo_out" "$CPINTEL_BACKUP_REMOTE/"
  echo "  copied to $CPINTEL_BACKUP_REMOTE"
else
  echo "  WARNING: CPINTEL_BACKUP_REMOTE is not set — these dumps exist only on this server."
fi

echo "$STAMP"
