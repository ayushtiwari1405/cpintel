#!/usr/bin/env bash
# Says what upgrading an existing PostgreSQL database past V35 will run into, before it does.
#
#   scripts/postgres-upgrade-check.sh --container cpintel-postgres
#
# Read-only: every query runs in a read-only session. Rows are named by id.
#
# Exit 0 ready, 3 the upgrade would stop (accounts differing only in capitals: rename or merge
# them first), 1 the database could not be checked.
#
# What else it lists does not stop the upgrade; it is what V37 and V38 will change, and the
# events they cannot judge, for a person to read first (docs/operations.md).
set -euo pipefail

CONTAINER=""
usage() { sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; }
while [ $# -gt 0 ]; do
  case "$1" in
    --container) CONTAINER="${2:-}"; shift ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown option: $1 (see --help)"; exit 2 ;;
  esac
  shift
done
[ -n "$CONTAINER" ] || { echo "!!! --container is required: name the PostgreSQL container to check."; exit 2; }
[[ "$CONTAINER" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || { echo "!!! Not a container name: $CONTAINER"; exit 2; }
[ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" = "true" ] \
  || { echo "!!! Container $CONTAINER is not running."; exit 1; }

# The query on stdin, one row per line. The account and database are the container's own, and the
# local socket needs no password.
query() {
  docker exec -i -e PGOPTIONS='-c default_transaction_read_only=on' "$CONTAINER" \
    sh -c 'exec psql -X -q -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
}

# Prints a heading and the rows, or that there are none; leaves the number of rows in FOUND.
report() {
  local title="$1" rows
  rows="$(query)" || { echo "!!! Could not query the database in $CONTAINER."; exit 1; }
  FOUND=0
  echo "$title"
  if [ -z "$rows" ]; then
    echo "  none"
  else
    sed 's/^/  /' <<<"$rows"
    FOUND="$(wc -l <<<"$rows")"
  fi
}

report "Accounts differing only in capitals (these STOP the upgrade)" <<'SQL'
SELECT 'username of users ' || string_agg(user_id::text, ', ' ORDER BY user_id)
  FROM users GROUP BY lower(username) HAVING count(*) > 1
UNION ALL
SELECT 'email of users ' || string_agg(user_id::text, ', ' ORDER BY user_id)
  FROM users GROUP BY lower(email) HAVING count(*) > 1;
SQL
BLOCKING=$FOUND

report "Teams that will be renamed (their id appended)" <<'SQL'
SELECT 'team ' || group_id || ' in classroom ' || classroom_id
  FROM (SELECT group_id, classroom_id,
               row_number() OVER (PARTITION BY classroom_id, lower(name) ORDER BY group_id) AS position
          FROM contest_groups) AS ranked
 WHERE position > 1 ORDER BY group_id;
SQL

report "Events that will go back to draft (nobody can have sat them)" <<'SQL'
SELECT lower(kind) || ' ' || contest_id || ' (' || lifecycle || ')'
  FROM group_contests c
 WHERE (ends_at < starts_at
        OR (lifecycle = 'ARCHIVED' AND (starts_at IS NULL OR archived_at < starts_at)))
   AND completed_at IS NULL
   AND NOT EXISTS (SELECT 1 FROM exam_events e WHERE e.contest_id = c.contest_id)
 ORDER BY contest_id;
SQL

report "Finished or archived examinations with no session log (check none was a draft ended by mistake)" <<'SQL'
SELECT 'exam ' || contest_id || ' (' || lifecycle || ', started ' || coalesce(starts_at::date::text, 'never') || ')'
  FROM group_contests c
 WHERE kind = 'EXAM' AND lifecycle <> 'DRAFT'
   AND (lifecycle = 'ARCHIVED' OR ends_at < now())
   AND NOT (ends_at < starts_at
            OR (lifecycle = 'ARCHIVED' AND (starts_at IS NULL OR archived_at < starts_at)))
   AND completed_at IS NULL
   AND NOT EXISTS (SELECT 1 FROM exam_events e WHERE e.contest_id = c.contest_id)
 ORDER BY contest_id;
SQL

report "Accounts whose avatar address will be cleared" <<'SQL'
SELECT 'user ' || user_id FROM users
 WHERE avatar_url IS NOT NULL AND avatar_url <> '' AND avatar_url !~* '^https?://\S+$'
 ORDER BY user_id;
SQL

if [ "$BLOCKING" -gt 0 ]; then
  echo "=== NOT ready: rename or merge the accounts listed above, then run this again."
  exit 3
fi
echo "=== ready to upgrade"
