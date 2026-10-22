#!/usr/bin/env bash
# Deploys one version of CPIntel on the server, and rolls back if it does not come up.
#
#   ./deploy.sh <version>      # a commit SHA that CI built and pushed to GHCR
#   ./deploy.sh --rollback     # back to the version before the current one
#   ./deploy.sh --force ...    # even while an examination is running or about to start
#
# In order:
#   1. back up both databases (the backend applies schema migrations as it starts, so this is
#      the copy to go back to if a migration goes wrong), and make sure the backend's own
#      MongoDB account exists (scripts/mongo-app-user.sh);
#   2. pull that version's images;
#   3. restart the application containers on them — the databases keep running;
#   4. wait for the backend to report healthy;
#   5. build the desktop installers for this server's address, if that or the app changed
#      (scripts/build-desktop.sh; 10-20 minutes when it does, nothing when it did not). The
#      site is already up on the new version meanwhile. CPINTEL_DESKTOP_BUILD=0 skips it;
#   6. on failure, put the previous version back. If the failed version had already migrated
#      the schema, say so and name the backup to restore: an older backend on a newer schema is
#      not something to leave running without a person deciding.
#
# Refused while an examination is running or starts within 30 minutes: the backend is down for
# the half minute or so it takes to start, and a room mid-paper sees requests fail for that long
# (nobody is signed out, and nothing saved is lost, but submissions wait). --force overrides it,
# for the fix that cannot wait.
set -euo pipefail

FORCE=0
args=()
for arg in "$@"; do
  if [ "$arg" = "--force" ]; then FORCE=1; else args+=("$arg"); fi
done
set -- ${args[@]+"${args[@]}"}

cd "$(dirname "${BASH_SOURCE[0]}")"
COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.prod.yml)
APP=(runner backend frontend nginx)
STATE_DIR=.deploy
mkdir -p "$STATE_DIR"
# Mounted into the backend; made here so Docker does not create it owned by root.
mkdir -p desktop

current="$(cat "$STATE_DIR/current" 2>/dev/null || true)"
previous="$(cat "$STATE_DIR/previous" 2>/dev/null || true)"

if [ "${1:-}" = "--rollback" ]; then
  [ -n "$previous" ] || { echo "No previous version recorded."; exit 1; }
  target="$previous"
else
  target="${1:?usage: ./deploy.sh <version> | --rollback}"
fi

schema_version() {
  docker exec cpintel-postgres sh -c 'psql -U "$POSTGRES_USER" -d cpintel -At -c \
    "select version from flyway_schema_history where success
     order by installed_rank desc limit 1"' 2>/dev/null || true
}

# Examinations running now or starting within 30 minutes, one per line: "name (ends 14:30)".
# Empty when there are none, or when the database is not up yet (a first deploy).
live_exams() {
  docker exec cpintel-postgres sh -c 'psql -U "$POSTGRES_USER" -d cpintel -At -c \
    "select name || '"'"' (until '"'"' || to_char(ends_at at time zone '"'"'Asia/Kolkata'"'"', '"'"'HH24:MI'"'"') || '"'"' IST)'"'"'
       from group_contests
      where kind = '"'"'EXAM'"'"' and lifecycle = '"'"'SCHEDULED'"'"' and completed_at is null
        and starts_at <= now() + interval '"'"'30 minutes'"'"' and ends_at > now()
      order by starts_at"' 2>/dev/null || true
}

healthy() {
  # The backend's port is not published in production; ask from inside its container.
  for _ in $(seq 1 60); do
    if docker exec cpintel-backend curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1
    then return 0; fi
    sleep 3
  done
  return 1
}

# A setting from .env, which compose reads by itself but this script does not.
env_value() {
  grep -m1 "^$1=" .env 2>/dev/null | cut -d= -f2- | tr -d "\"'" || true
}

# The installers are built for the address people open CPIntel at. A failed build leaves the
# previous installers in place and does not fail the deploy: the site itself is up.
build_desktop() {
  local enabled url
  enabled="${CPINTEL_DESKTOP_BUILD:-$(env_value CPINTEL_DESKTOP_BUILD)}"
  [ "$enabled" = "0" ] && return 0
  url="$(env_value CPINTEL_PUBLIC_URL)"
  if [ -z "$url" ] || [ "$url" = "http://localhost" ]; then
    echo "--- CPINTEL_PUBLIC_URL is not set in .env; desktop installers not built."
    return 0
  fi
  ./scripts/build-desktop.sh "$url" \
    || echo "!!! The desktop build failed; the download page keeps the previous installers."
}

run_version() {
  CPINTEL_VERSION="$1" "${COMPOSE[@]}" up -d --remove-orphans "${APP[@]}"
}

echo "=== CPIntel deploy: ${current:-none} → $target ==="

exams="$(live_exams)"
if [ -n "$exams" ] && [ "$FORCE" != "1" ]; then
  echo "!!! Not deploying: an examination is running or starts within 30 minutes:"
  echo "$exams" | sed 's/^/!!!     /'
  echo "!!! The backend restarts, and the room would see requests fail while it does."
  echo "!!! Deploy after it ends, or pass --force if this cannot wait."
  exit 1
fi
[ -n "$exams" ] && echo "--- --force: deploying during $(echo "$exams" | head -1)"

# The databases have to be up to be backed up — and on a first deploy, to exist at all.
CPINTEL_VERSION="$target" "${COMPOSE[@]}" up -d postgres mongodb redis

backup=""
if [ -n "$current" ]; then
  backup="$(./scripts/backup.sh "pre-deploy-$target" | tail -1)"
fi
# The backend signs in to MongoDB with its own least-privilege account (MONGO_APP_USER). A
# database created before that account existed gets it here - before any application container
# is touched, so a failure leaves the running version as it was.
./scripts/mongo-app-user.sh || { echo "!!! The backend's MongoDB account is not ready; not deploying."; exit 1; }

schema_before="$(schema_version)"

CPINTEL_VERSION="$target" "${COMPOSE[@]}" pull "${APP[@]}"
run_version "$target"

# nginx looks the backend's address up when it starts; a recreated backend container can come
# back on another one, and nginx would keep sending to the old address. A reload looks again.
reload_nginx() {
  docker exec cpintel-nginx nginx -s reload >/dev/null 2>&1 || true
}

if healthy; then
  reload_nginx
  if [ "$target" != "$current" ]; then
    echo "$current" > "$STATE_DIR/previous"
    echo "$target" > "$STATE_DIR/current"
  fi
  docker image prune -f >/dev/null
  echo "=== deployed $target (schema ${schema_before:-new} → $(schema_version)) ==="
  build_desktop
  exit 0
fi

echo "!!! $target did not become healthy. Last backend log lines:"
docker logs --tail 30 cpintel-backend 2>&1 | sed 's/^/    /'

if [ -z "$current" ]; then
  echo "!!! No previous version to return to. Fix and deploy again."
  exit 1
fi

schema_after="$(schema_version)"
echo "!!! Rolling back to $current."
run_version "$current"

if [ "$schema_after" != "$schema_before" ]; then
  echo "!!! $target migrated the schema ($schema_before → $schema_after) before failing."
  echo "!!! $current may not work on the newer schema. If it does not, restore the backup"
  echo "!!! taken just before this deploy:"
  echo "!!!     docker compose stop backend && ./scripts/restore.sh $backup"
fi

if healthy; then
  reload_nginx
  echo "=== rolled back to $current ==="
else
  echo "!!! $current is not healthy either. See: docker logs cpintel-backend"
fi
exit 1
