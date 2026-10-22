#!/usr/bin/env bash
# Checks, and prepares, an existing MongoDB for a backend that creates its own named indexes.
# The full procedure - backup, stopping the backend, the order of these commands, recovery - is
# README.md, "Upgrading MongoDB's indexes". In short:
#
#   scripts/mongo-index-upgrade.sh --container cpintel-mongo --check
#       Read-only. Duplicates against each unique index, what each index needs, and whether
#       anything is still connected that could write. Exit 0 ready, 3 duplicates, 1 unsafe.
#   scripts/mongo-index-upgrade.sh --container cpintel-mongo --export DIR
#       Read-only. As --check, and writes every conflicting document to DIR/<run>/ - personal
#       files without their content - for review and as a record.
#   scripts/mongo-index-upgrade.sh --container cpintel-mongo --rename-duplicate-files DIR
#       Read-only. Plans names for duplicate personal files ("name (2).cpp") into DIR/<run>/.
#   scripts/mongo-index-upgrade.sh --container cpintel-mongo --rename-duplicate-files DIR \
#       --confirm --backup /var/backups/cpintel/mongo-<stamp>.archive.gz
#       Writes the plan first, then renames: only the name changes; content, owner and dates stay.
#   scripts/mongo-index-upgrade.sh --container cpintel-mongo --apply \
#       --backup /var/backups/cpintel/mongo-<stamp>.archive.gz
#       Creates and renames the managed indexes. Refuses while duplicates remain, while the
#       backend (or anything as its account) is connected, or on any index it does not recognise.
#
# What it never does: delete a document, drop an index it has not matched exactly against a
# known earlier definition, use dropIndexes(), touch _id_, or choose between duplicate contest
# rules or Codeforces submissions - those are exported for a person to decide (README).
# Safe to run again at any point: every step checks the current state first.
#
# Settings: MONGO_APP_USER (default cpintel_app) - the backend's account, whose connections count
# as writers. CPINTEL_BACKUP_MAX_AGE_HOURS (default 24) - how old --backup may be.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JS="$ROOT/scripts/mongo-index-upgrade.js"
DATABASE=cpintel
CONTAINER="" MODE="" OUT_DIR="" BACKUP="" CONFIRM=0

usage() { sed -n '2,32p' "$0"; }
while [ $# -gt 0 ]; do
  case "$1" in
    --container) CONTAINER="${2:-}"; shift ;;
    --check) MODE="check" ;;
    --export) MODE="export"; OUT_DIR="${2:-}"; shift ;;
    --rename-duplicate-files) MODE="rename"; OUT_DIR="${2:-}"; shift ;;
    --apply) MODE="apply" ;;
    --confirm) CONFIRM=1 ;;
    --backup) BACKUP="${2:-}"; shift ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown option: $1 (see --help)"; exit 2 ;;
  esac
  shift
done

[ -n "$MODE" ] || { usage; exit 2; }
[ -n "$CONTAINER" ] || { echo "!!! --container is required: name the MongoDB container to work on; nothing is assumed."; exit 2; }
[[ "$CONTAINER" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || { echo "!!! Not a container name: $CONTAINER"; exit 2; }
case "$MODE" in export|rename) [ -n "$OUT_DIR" ] || { echo "!!! --$MODE needs a directory for its files."; exit 2; } ;; esac
[ "$CONFIRM" = 0 ] || [ "$MODE" = rename ] || { echo "!!! --confirm goes with --rename-duplicate-files."; exit 2; }
APP_USER="${MONGO_APP_USER:-cpintel_app}"
MUTATES=0
{ [ "$MODE" = apply ] || { [ "$MODE" = rename ] && [ "$CONFIRM" = 1 ]; }; } && MUTATES=1

# -- the target --------------------------------------------------------------------------------------
[ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" = "true" ] \
  || { echo "!!! Container $CONTAINER is not running."; exit 1; }
docker exec "$CONTAINER" sh -c '[ -n "$MONGO_INITDB_ROOT_USERNAME" ] && command -v mongosh >/dev/null' 2>/dev/null \
  || { echo "!!! $CONTAINER is not a MongoDB container with a root account (MONGO_INITDB_ROOT_*)."; exit 1; }

# The JavaScript on stdin, as the container's root account - fed as a private temporary file, as
# scripts/mongo-app-user.sh does, so no value reaches a command line on this machine.
root_mongosh() {
  docker exec -i "$CONTAINER" sh -c 'umask 077; f=$(mktemp); cat > "$f"; \
    mongosh --quiet -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" \
      --authenticationDatabase admin --file "$f"; status=$?; rm -f "$f"; exit $status'
}
for _ in $(seq 1 30); do
  echo 'print(db.adminCommand({ ping: 1 }).ok)' | root_mongosh 2>/dev/null | grep -qx 1 && break
  sleep 1
done
echo 'print(db.adminCommand({ ping: 1 }).ok)' | root_mongosh 2>/dev/null | grep -qx 1 \
  || { echo "!!! Could not sign in to $CONTAINER as its root account."; exit 1; }
project="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$CONTAINER" 2>/dev/null || true)"
echo "Target: database '$DATABASE' in container $CONTAINER${project:+ (Compose project $project)}"

# -- the backup, before anything that changes data or indexes ------------------------------------------
check_backup() {
  local max_hours="${CPINTEL_BACKUP_MAX_AGE_HOURS:-24}" age
  [ -n "$BACKUP" ] || { echo "!!! --backup is required: a dump from scripts/backup.sh (mongo-<stamp>.archive.gz), taken after the backend was stopped."; exit 4; }
  [ -f "$BACKUP" ] && [ -s "$BACKUP" ] || { echo "!!! The backup $BACKUP is not a non-empty file."; exit 4; }
  [[ "$(basename "$BACKUP")" =~ ^mongo-.+\.archive\.gz$ ]] || { echo "!!! $BACKUP is not named as scripts/backup.sh names a MongoDB dump."; exit 4; }
  age=$(( ($(date +%s) - $(stat -c %Y "$BACKUP")) / 3600 ))
  [ "$age" -le "$max_hours" ] || { echo "!!! The backup is ${age} h old (allowed: ${max_hours} h). Take a fresh one with the backend stopped."; exit 4; }
  gzip -t "$BACKUP" 2>/dev/null || { echo "!!! The backup is not a readable gzip file."; exit 4; }
  # Read through by mongorestore without writing anything.
  docker exec -i "$CONTAINER" sh -c 'mongorestore --quiet --dryRun --archive --gzip --nsInclude "cpintel.*" \
    -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin' \
    < "$BACKUP" >/dev/null 2>&1 || { echo "!!! mongorestore could not read the backup as a MongoDB dump."; exit 4; }
  echo "Backup: $(basename "$BACKUP"), ${age} h old, readable"
}
[ "$MUTATES" = 0 ] || check_backup

# -- running the JavaScript ----------------------------------------------------------------------------
RUN_DIR=""
if [ -n "$OUT_DIR" ]; then
  RUN_DIR="$OUT_DIR/mongo-index-upgrade-$(date -u +%Y%m%dT%H%M%SZ)-$$"
  ( umask 077; mkdir -p "$RUN_DIR" )
fi

# Runs scripts/mongo-index-upgrade.js in <mode>; shows SAY lines, files EXPORT lines, prints the status.
STATUS=""
run_js() {   # <mode> [file with one Extended JSON rename per line]
  local mode="$1" renames="${2:-}" line kind rest file
  STATUS=""
  while IFS= read -r line; do
    kind="${line%% *}"; rest="${line#* }"
    case "$kind" in
      SAY) printf '%s\n' "$rest" ;;
      EXPORT)
        file="${rest%% *}"
        [ -n "$RUN_DIR" ] || { echo "!!! Unexpected export"; exit 1; }
        ( umask 077; printf '%s\n' "${rest#* }" >> "$RUN_DIR/$file" ) ;;
      STATUS) STATUS="$rest" ;;
      *) [ -z "$line" ] || printf '  %s\n' "$line" ;;
    esac
  done < <( { MODE_="$mode" DB_="$DATABASE" APP_="$APP_USER" RENAMES_="$renames" python3 -I -c '
import json, os
renames = []
if os.environ["RENAMES_"]:
    renames = [l.rstrip("\n") for l in open(os.environ["RENAMES_"]) if l.strip()]
print("const PARAMS = " + json.dumps({"mode": os.environ["MODE_"], "database": os.environ["DB_"],
      "appUser": os.environ["APP_"], "renames": renames}) + ";")'
        cat "$JS"; } | root_mongosh 2>&1 )
}

finish() {   # the exit code for a status, with a word about what to do
  case "$STATUS" in
    READY)   echo "=== Ready: no duplicates, and every index is in place or can be made."; exit 0 ;;
    APPLIED) echo "=== Done: every managed index is in place. Start the backend."; exit 0 ;;
    PLANNED|RENAMED) exit 0 ;;
    DUPLICATES) echo "=== Duplicates remain: resolve them (README) and check again. Nothing was changed."; exit 3 ;;
    WRITERS) echo "=== Refused: something is connected that can write (above). Stop the backend first. Nothing was changed."; exit 4 ;;
    UNSAFE)  echo "=== Stopped: something above needs a person. Nothing further was changed."; exit 1 ;;
    *)       echo "!!! No answer from the check (status '${STATUS}')."; exit 1 ;;
  esac
}

case "$MODE" in
  check|export|apply)
    run_js "$MODE"
    [ -z "$RUN_DIR" ] || echo "Exported conflicting documents to $RUN_DIR (personal files without their content)."
    finish ;;
  rename)
    run_js rename-plan
    plan="$RUN_DIR/personal_files.renames.jsonl"
    if [ -f "$RUN_DIR/RENAME" ]; then mv "$RUN_DIR/RENAME" "$plan"; else : > "$plan"; fi
    sync "$plan" 2>/dev/null || sync
    echo "Plan: $plan ($(grep -c . "$plan" || true) rename(s): _id, user, old and new name)"
    [ "$STATUS" = PLANNED ] || finish
    if [ "$CONFIRM" = 0 ]; then
      echo "=== Nothing was changed. Review the plan, then run again with --confirm --backup <dump>."
      exit 0
    fi
    [ -s "$plan" ] || { echo "=== No duplicate file names to rename."; exit 0; }
    run_js rename-apply "$plan"
    finish ;;
esac
