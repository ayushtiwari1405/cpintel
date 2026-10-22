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
# What a backup is: the cpintel database of each, data and index definitions. Not in it: database
# accounts and roles (Postgres roles, MongoDB's root and the backend's cpintel_app - these come
# from .env and scripts/mongo-app-user.sh), .env itself and its keys, Redis (sessions and grants,
# rebuilt as people sign in; exam grants are in Postgres too), and anything outside the
# databases. A dump is kept only if it reads back (pg_restore --list, gzip -t); if the MongoDB
# one fails, the Postgres one of the same run is removed too, so a stamp is always a pair.
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
# The containers, by name; set only to point at another stack, as the restore drill does.
PG_CONTAINER="${CPINTEL_PG_CONTAINER:-cpintel-postgres}"
MONGO_CONTAINER="${CPINTEL_MONGO_CONTAINER:-cpintel-mongo}"
KEEP="${CPINTEL_BACKUP_KEEP_DAYS:-14}"
TAG="${1:-nightly}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)-$TAG"

mkdir -p "$DIR"
chmod 700 "$DIR"

echo "[$(date -u +%FT%TZ)] backup $STAMP → $DIR"

# Written under a temporary name, read back, and only then renamed, so a dump cut short by a
# full disk or a killed container never sits in the directory looking like a good one. A run
# that fails removes its own partial files.
pg_out="$DIR/postgres-$STAMP.dump"
mongo_out="$DIR/mongo-$STAMP.archive.gz"
trap 'rm -f "$pg_out.partial" "$mongo_out.partial"' EXIT
docker exec "$PG_CONTAINER" sh -c 'pg_dump -U "$POSTGRES_USER" -d cpintel -Fc' \
  > "$pg_out.partial"
docker exec -i "$PG_CONTAINER" pg_restore --list < "$pg_out.partial" > /dev/null \
  || { echo "!!! The Postgres dump does not read back; nothing was kept."; exit 1; }
mv "$pg_out.partial" "$pg_out"

# For mongodump and mongorestore, which cannot read a script: the root password in a private file
# in the container, given as --config and removed on exit, rather than as -p on a command line.
read -r -d '' MONGO_TOOLS_AUTH <<'SH' || true
umask 077
c=$(mktemp)
trap 'rm -f "$c"' EXIT
printf 'password: "%s"\n' "$(printf %s "$MONGO_INITDB_ROOT_PASSWORD" | sed 's/[\\"]/\\&/g')" > "$c"
SH
docker exec "$MONGO_CONTAINER" sh -c "$MONGO_TOOLS_AUTH"'
  mongodump --quiet --archive --gzip --db cpintel \
    -u "$MONGO_INITDB_ROOT_USERNAME" --config "$c" --authenticationDatabase admin' > "$mongo_out.partial" \
  || { echo "!!! mongodump failed; nothing was kept."; rm -f "$pg_out"; exit 1; }
gzip -t "$mongo_out.partial" 2>/dev/null && [ -s "$mongo_out.partial" ] \
  || { echo "!!! The MongoDB dump does not read back; nothing was kept."; rm -f "$pg_out"; exit 1; }
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
