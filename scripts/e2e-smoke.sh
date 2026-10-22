#!/usr/bin/env bash
# Opt-in browser smoke test: starts a throwaway CPIntel, seeds it, and drives the main journeys in
# a real Chrome (frontend/e2e/smoke.test.mjs). Not part of `npm test` or CI.
#
#   scripts/e2e-smoke.sh                   run, then take everything it started down again
#   scripts/e2e-smoke.sh --keep            leave the stack up afterwards (prints how to stop it)
#   scripts/e2e-smoke.sh --rebuild         rebuild the backend jar first
#   scripts/e2e-smoke.sh --mongo-upgrade   start MongoDB as an installation from before the backend
#                                          had its own account, and give it one with
#                                          scripts/mongo-app-user.sh, as deploy.sh would
#   scripts/e2e-smoke.sh --cleanup         list containers left behind by earlier runs (removes nothing)
#   scripts/e2e-smoke.sh --cleanup --yes   remove them
#
# What it needs: Docker, Java 21, Node 24 (with frontend/node_modules installed), and a Chrome or
# Chromium binary - found on PATH, or set E2E_CHROME. Docker pulls postgres, mongo and redis
# images if they are not present.
#
# What it touches, and what it never does:
#   - Its containers are named cpintel-e2e-<role>-<run id>, labelled org.cpintel.e2e, started
#     with --rm, published on free loopback ports, and given no named volume: removing one takes
#     its data with it. The development stack (cpintel-postgres and friends) and every cpintel_*
#     volume are left alone, and the ports they use are never taken.
#   - Passwords and keys are generated for the run, kept in a private temporary directory, and
#     deleted with it; none is printed.
#   - The frontend is built into that temporary directory; frontend/dist is not touched.
#   - Codeforces is stubbed (frontend/e2e/fixtures.mjs): a run makes no call to the real site.
#   - Cleanup removes only what this run started (by name), and --cleanup only containers that
#     carry both the label and the name prefix.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LABEL=org.cpintel.e2e
PREFIX=cpintel-e2e-
KEEP=0 REBUILD=0 CLEANUP=0 YES=0 MONGO_UPGRADE=0
for arg in "$@"; do
  case "$arg" in
    --keep) KEEP=1 ;;
    --rebuild) REBUILD=1 ;;
    --cleanup) CLEANUP=1 ;;
    --yes) YES=1 ;;
    --mongo-upgrade) MONGO_UPGRADE=1 ;;
    *) echo "Unknown option: $arg (see the top of $0)"; exit 2 ;;
  esac
done

say() { printf '\n=== %s\n' "$*"; }
die() { printf '!!! %s\n' "$*" >&2; exit 1; }

# -- leftovers from earlier runs -----------------------------------------------------------------
if [ "$CLEANUP" = 1 ]; then
  mapfile -t rows < <(docker ps -a --filter "label=$LABEL=1" --format '{{.ID}} {{.Names}} {{.Status}}' \
    | awk -v p="$PREFIX" 'index($2, p) == 1')
  if [ "${#rows[@]}" = 0 ]; then echo "No smoke-test containers to remove."; exit 0; fi
  printf '%s\n' "${rows[@]}"
  if [ "$YES" != 1 ]; then echo "Dry run: nothing removed. Add --yes to remove the containers above."; exit 0; fi
  for row in "${rows[@]}"; do docker rm -f -v "${row%% *}" >/dev/null && echo "removed ${row#* }"; done
  exit 0
fi

# -- preflight ----------------------------------------------------------------------------------
say "Preflight"
docker info >/dev/null 2>&1 || die "Docker is not reachable."
java -version 2>&1 | grep -qE 'version "(2[1-9]|[3-9][0-9])' || die "Java 21 or newer is needed for the backend."
node -e 'process.exit(Number(process.versions.node.split(".")[0]) >= 24 ? 0 : 1)' \
  || die "Node 24 is needed (this is $(node -v))."
[ -d "$ROOT/frontend/node_modules/playwright-core" ] || die "Run npm ci in frontend/ first."
CHROME="${E2E_CHROME:-$(command -v google-chrome || command -v google-chrome-stable || command -v chromium || command -v chromium-browser || true)}"
[ -n "$CHROME" ] && [ -x "$CHROME" ] || die "No Chrome or Chromium found; set E2E_CHROME to one."

RUN="$(date -u +%Y%m%d%H%M%S)-$$"
TMP="$(mktemp -d -t cpintel-e2e.XXXXXX)"
chmod 700 "$TMP"
free_port() { node -e 'const s=require("net").createServer();s.listen(0,"127.0.0.1",()=>{console.log(s.address().port);s.close()})'; }
PG_PORT=$(free_port) MONGO_PORT=$(free_port) REDIS_PORT=$(free_port) API_PORT=$(free_port) WEB_PORT=$(free_port)
echo "run $RUN; ports: postgres $PG_PORT, mongo $MONGO_PORT, redis $REDIS_PORT, api $API_PORT, web $WEB_PORT"
echo "chrome: $CHROME ($("$CHROME" --version 2>/dev/null || echo unknown version))"

CONTAINERS=() PIDS=()
cleanup() {
  local code=$?
  if [ "$KEEP" = 1 ]; then
    say "Left running (--keep): open http://127.0.0.1:$WEB_PORT"
    echo "Stop it with: kill ${PIDS[*]:-}; docker stop ${CONTAINERS[*]:-}; rm -rf $TMP"
    return
  fi
  say "Cleaning up run $RUN"
  for pid in "${PIDS[@]}"; do kill "$pid" 2>/dev/null || true; done
  # --rm: stopping removes the container and its anonymous volume.
  for name in "${CONTAINERS[@]}"; do docker stop -t 5 "$name" >/dev/null 2>&1 || true; done
  rm -rf "$TMP"
  exit "$code"
}
trap cleanup EXIT INT TERM

secret() { openssl rand -hex "$1"; }
export PG_PW="$(secret 16)" MONGO_PW="$(secret 16)" MONGO_APP_PASSWORD="$(secret 16)" REDIS_PW="$(secret 16)"
export E2E_ADMIN_PASSWORD="$(secret 12)Aa1!" E2E_STUDENT_PASSWORD="$(secret 12)Bb2!" E2E_ADMIN2_PASSWORD="$(secret 12)Cc3!"

# -- datastores -----------------------------------------------------------------------------------
say "Starting throwaway datastores"
start() {   # <role> <docker run arguments...>
  local name="${PREFIX}$1-$RUN"; shift
  docker run -d --rm --name "$name" --label "$LABEL=1" --label "$LABEL.run=$RUN" "$@" >/dev/null
  CONTAINERS+=("$name")
}
start postgres -e POSTGRES_DB=cpintel -e POSTGRES_USER=cpintel -e POSTGRES_PASSWORD="$PG_PW" \
  -p "127.0.0.1:$PG_PORT:5432" postgres:16-alpine
# The backend signs in as the least-privilege account (MONGO_APP_USER), as in docker-compose.yml.
# A fresh installation gets it from mongo-init.js; --mongo-upgrade starts without it, as a database
# made before it existed, and has scripts/mongo-app-user.sh add it below.
MONGO_INIT=(-e MONGO_APP_USER=cpintel_app -e MONGO_APP_PASSWORD="$MONGO_APP_PASSWORD"
  -v "$ROOT/mongo-init.js:/docker-entrypoint-initdb.d/init.js:ro")
[ "$MONGO_UPGRADE" = 1 ] && MONGO_INIT=()
start mongo -e MONGO_INITDB_ROOT_USERNAME=cpintel_mongo -e MONGO_INITDB_ROOT_PASSWORD="$MONGO_PW" \
  -e MONGO_INITDB_DATABASE=cpintel "${MONGO_INIT[@]}" -p "127.0.0.1:$MONGO_PORT:27017" mongo:7.0
start redis -p "127.0.0.1:$REDIS_PORT:6379" redis:7.2-alpine redis-server --requirepass "$REDIS_PW"
PG="${PREFIX}postgres-$RUN" MONGO="${PREFIX}mongo-$RUN"
for _ in $(seq 1 60); do docker exec "$PG" pg_isready -U cpintel -d cpintel >/dev/null 2>&1 && break; sleep 1; done
for _ in $(seq 1 60); do
  docker exec "$MONGO" mongosh --quiet -u cpintel_mongo -p "$MONGO_PW" --authenticationDatabase admin \
    --eval 'db.runCommand({ ping: 1 }).ok' >/dev/null 2>&1 && break; sleep 1
done
# The entrypoint answers from a temporary mongod while it initialises, then restarts it.
for _ in $(seq 1 60); do
  [ "$(docker logs "$MONGO" 2>&1 | grep -c 'Waiting for connections')" -ge 2 ] && break; sleep 1
done
if [ "$MONGO_UPGRADE" = 1 ]; then
  say "Upgrading MongoDB: data from before, then the backend's account"
  docker exec "$MONGO" mongosh --quiet -u cpintel_mongo -p "$MONGO_PW" --authenticationDatabase admin cpintel \
    --eval 'db.personal_files.insertOne({ userId: 999, name: "from-before.cpp", content: "int main(){}" })' >/dev/null
  CPINTEL_MONGO_CONTAINER="$MONGO" MONGO_APP_USER=cpintel_app "$ROOT/scripts/mongo-app-user.sh"
fi

# -- frontend and the stub in front of everything --------------------------------------------
say "Building the frontend into the run's directory"
(cd "$ROOT/frontend" && node scripts/copy-mathjax.mjs >/dev/null && npx vite build --outDir "$TMP/dist" --emptyOutDir --logLevel warn)
node "$ROOT/frontend/e2e/server.mjs" "$TMP/dist" "$WEB_PORT" "http://127.0.0.1:$API_PORT" > "$TMP/web.log" 2>&1 &
PIDS+=($!)

# -- backend ----------------------------------------------------------------------------------------
JAR="$(ls -t "$ROOT"/backend/target/cpintel-backend-*.jar 2>/dev/null | head -1 || true)"
if [ "$REBUILD" = 1 ] || [ -z "$JAR" ]; then
  say "Building the backend jar"
  (cd "$ROOT/backend" && ./mvnw -q package -DskipTests -Djacoco.skip=true)
  JAR="$(ls -t "$ROOT"/backend/target/cpintel-backend-*.jar | head -1)"
elif [ -n "$(find "$ROOT/backend/src/main" -newer "$JAR" -type f -print -quit)" ]; then
  echo "Note: backend sources changed since $(basename "$JAR") was built; --rebuild to test them."
fi
say "Starting the backend"
: > "$TMP/backend.log"
SERVER_PORT="$API_PORT" \
SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$PG_PORT/cpintel" SPRING_DATASOURCE_USERNAME=cpintel \
SPRING_DATASOURCE_PASSWORD="$PG_PW" \
SPRING_DATA_MONGODB_URI="mongodb://cpintel_app:$MONGO_APP_PASSWORD@127.0.0.1:$MONGO_PORT/cpintel?authSource=cpintel" \
SPRING_DATA_REDIS_HOST=127.0.0.1 SPRING_DATA_REDIS_PORT="$REDIS_PORT" SPRING_DATA_REDIS_PASSWORD="$REDIS_PW" \
JWT_SECRET="$(openssl rand -base64 48 | tr -d '\n')" \
CPINTEL_EXAM_PASSWORD_KEY="$(openssl rand -base64 32 | tr -d '\n')" \
CPINTEL_DOMJUDGE_CREDENTIAL_KEY="$(openssl rand -base64 32 | tr -d '\n')" \
CPINTEL_SESSION_KEY="$(secret 24)" CPINTEL_RUNNER_TOKEN="$(secret 24)" \
CPINTEL_ADMIN_EMAIL=e2e-superadmin@cpintel.test CPINTEL_ADMIN_USERNAME=superadmin CPINTEL_ADMIN_PASSWORD="$E2E_ADMIN_PASSWORD" \
CPINTEL_RATE_LIMIT_ENABLED=false CPINTEL_RUNNER_ENABLED=false \
CPINTEL_PLATFORMS_CODEFORCES_BASEURL="http://127.0.0.1:$WEB_PORT/cf/api" \
  java -jar "$JAR" > "$TMP/backend.log" 2>&1 &
PIDS+=($!)
for _ in $(seq 1 120); do
  grep -qE 'Started CpIntelApplication' "$TMP/backend.log" && break
  grep -qE 'APPLICATION FAILED TO START' "$TMP/backend.log" && { tail -30 "$TMP/backend.log"; die "The backend did not start."; }
  kill -0 "${PIDS[-1]}" 2>/dev/null || { tail -30 "$TMP/backend.log"; die "The backend exited."; }
  sleep 1
done
grep -q 'Started CpIntelApplication' "$TMP/backend.log" || die "The backend took too long to start."

# -- seed and run -------------------------------------------------------------------------------
say "Seeding"
export E2E_API="http://127.0.0.1:$API_PORT"
node "$ROOT/frontend/e2e/seed.mjs" accounts "$TMP"
docker exec -i "$MONGO" mongosh --quiet -u cpintel_mongo -p "$MONGO_PW" --authenticationDatabase admin < "$TMP/mongo-seed.js" | tail -1
node "$ROOT/frontend/e2e/seed.mjs" refresh

say "Running the smoke test"
mkdir -p "$TMP/shots"
E2E_BASE="http://127.0.0.1:$WEB_PORT" E2E_CHROME="$CHROME" E2E_SHOTS="${E2E_SHOTS:-$TMP/shots}" \
  node --test --test-reporter=spec "$ROOT/frontend/e2e/smoke.test.mjs"
