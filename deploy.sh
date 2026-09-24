#!/usr/bin/env bash
# Deploys one version of CPIntel on the server, and rolls back if it does not come up.
#
#   ./deploy.sh <version>      # a commit SHA that CI built and pushed to GHCR
#   ./deploy.sh --rollback     # back to the version before the current one
#
# In order:
#   1. back up both databases (the backend applies schema migrations as it starts, so this is
#      the copy to go back to if a migration goes wrong);
#   2. pull that version's images;
#   3. restart the application containers on them — the databases keep running;
#   4. wait for the backend to report healthy;
#   5. on failure, put the previous version back. If the failed version had already migrated
#      the schema, say so and name the backup to restore: an older backend on a newer schema is
#      not something to leave running without a person deciding.
#
# Never while an examination is running: the restart drops every live session for a minute.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.prod.yml)
APP=(runner backend frontend nginx)
STATE_DIR=.deploy
mkdir -p "$STATE_DIR"

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

healthy() {
  # The backend's port is not published in production; ask from inside its container.
  for _ in $(seq 1 60); do
    if docker exec cpintel-backend curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1
    then return 0; fi
    sleep 3
  done
  return 1
}

run_version() {
  CPINTEL_VERSION="$1" "${COMPOSE[@]}" up -d --remove-orphans "${APP[@]}"
}

echo "=== CPIntel deploy: ${current:-none} → $target ==="

# The databases have to be up to be backed up — and on a first deploy, to exist at all.
CPINTEL_VERSION="$target" "${COMPOSE[@]}" up -d postgres mongodb redis

backup=""
if [ -n "$current" ]; then
  backup="$(./scripts/backup.sh "pre-deploy-$target" | tail -1)"
fi
schema_before="$(schema_version)"

CPINTEL_VERSION="$target" "${COMPOSE[@]}" pull "${APP[@]}"
run_version "$target"

if healthy; then
  if [ "$target" != "$current" ]; then
    echo "$current" > "$STATE_DIR/previous"
    echo "$target" > "$STATE_DIR/current"
  fi
  docker image prune -f >/dev/null
  echo "=== deployed $target (schema ${schema_before:-new} → $(schema_version)) ==="
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
  echo "=== rolled back to $current ==="
else
  echo "!!! $current is not healthy either. See: docker logs cpintel-backend"
fi
exit 1
