#!/usr/bin/env bash
# Opt-in full-stack integration test: the production images (built from this checkout) under a
# throwaway Compose project, through a fresh install and an upgrade from the previous release.
# Not part of CI or ci-local.sh - it builds every image and takes several minutes.
#
#   scripts/integration-test.sh                  both phases, then remove everything they made
#   scripts/integration-test.sh --only fresh     one phase (fresh | upgrade)
#   scripts/integration-test.sh --keep           leave the last phase's stack up (prints how to stop it)
#   scripts/integration-test.sh --cleanup        list what earlier runs left behind (removes nothing)
#   scripts/integration-test.sh --cleanup --yes  remove it
#
# Fresh install: postgres, mongodb (with today's mongo-init.js), redis, backend, frontend and
# nginx. Checks the backend's MongoDB account is readWrite on cpintel only and that the backend
# created its own unique indexes, runs the API
# workflow in integration/api_checks.py through nginx, stops MongoDB and expects health to say
# DOWN rather than UP, starts it again, and checks that no generated secret or reset link
# reached any container's log.
#
# Upgrade: MongoDB starts from the previous release's mongo-init.js (integration/fixtures/), as
# a server installed before P6 would have it, and gets data written as root. Then the
# documented upgrade (README, "MongoDB accounts"): scripts/mongo-app-user.sh creates the
# account (and finds nothing to do a second time); the backend, started on the old indexes,
# must fail with IndexOptionsConflict rather than come up; the documented index drop follows;
# then the backend must come up, the data must still be there, and the API must work.
#
# What it touches, and what it never does:
#   - Each phase is a Compose project of its own, cpintel-it-<run id>[-upgrade]: its containers
#     are named after it, its volumes and networks carry its prefix, and nothing is published
#     but nginx's 443 on a free 127.0.0.1 port. integration/isolation.py checks the resolved
#     configuration for exactly that before anything starts, and the run stops if it fails.
#     The development stack (project cpintel, cpintel_* volumes) is never named or touched.
#   - The runner, monitoring and certbot are not started; the backend does not need them to
#     start or to serve the workflow above.
#   - Secrets are generated for the run into a private temporary directory, passed to Compose
#     as an env file, and deleted with it. None is printed; diagnostics are redacted.
#   - Cleanup is `docker compose -p <that project> down -v --rmi local` for the projects this
#     run created, so it removes their containers, volumes, network and the images Compose
#     built for them, and nothing else. The Docker build cache is kept.
#
# Needs: Docker with Compose v2.24 or later (!reset/!override), and python3. Time: a first run
# builds the backend, frontend and nginx images (several minutes); later runs reuse the build
# cache.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HERE="$ROOT/scripts/integration"
PHASES=(fresh upgrade) KEEP=0 CLEANUP=0 YES=0

while [ $# -gt 0 ]; do
  case "$1" in
    --only) shift; case "${1:-}" in fresh|upgrade) PHASES=("$1") ;; *) echo "--only fresh|upgrade"; exit 2 ;; esac ;;
    --keep) KEEP=1 ;;
    --cleanup) CLEANUP=1 ;;
    --yes) YES=1 ;;
    *) sed -n '2,10p' "$0"; exit 2 ;;
  esac
  shift
done

# -- leftovers ------------------------------------------------------------------------------------
# Only projects named cpintel-it-*: containers, volumes and networks by Compose's project label,
# images by the <project>-<service> name Compose gives them.
leftover_projects() {
  { docker ps -a --format '{{.Label "com.docker.compose.project"}}'
    docker volume ls --format '{{.Label "com.docker.compose.project"}}'
    docker network ls --format '{{.Label "com.docker.compose.project"}}'
    docker images --format '{{.Repository}}' | sed -nE 's/^(cpintel-it-[a-z0-9-]+)-(backend|frontend|nginx|runner)$/\1/p'
  } | grep -E '^cpintel-it-[a-z0-9][a-z0-9-]*$' | sort -u || true
}
remove_project() {   # <project>: only that project's containers, volumes, networks and built images
  local p="$1"
  [[ "$p" =~ ^cpintel-it-[a-z0-9][a-z0-9-]*$ ]] || { echo "!!! refusing to remove project '$p'"; return 1; }
  docker ps -aq --filter "label=com.docker.compose.project=$p" | xargs -r docker rm -f -v >/dev/null
  docker volume ls -q --filter "label=com.docker.compose.project=$p" | xargs -r docker volume rm >/dev/null
  docker network ls -q --filter "label=com.docker.compose.project=$p" | xargs -r docker network rm >/dev/null
  # Compose names the images it builds <project>-<service> and labels them with no project.
  docker images --format '{{.Repository}}:{{.Tag}}' | grep -E "^$p-(backend|frontend|nginx|runner):" \
    | xargs -r docker rmi >/dev/null 2>&1 || true
}
if [ "$CLEANUP" = 1 ]; then
  mapfile -t projects < <(leftover_projects)
  [ "${#projects[@]}" -gt 0 ] || { echo "Nothing left behind by earlier runs."; exit 0; }
  echo "Left behind by earlier runs:"; printf '  %s\n' "${projects[@]}"
  [ "$YES" = 1 ] || { echo "Run again with --cleanup --yes to remove them."; exit 0; }
  for p in "${projects[@]}"; do remove_project "$p" && echo "Removed $p."; done
  exit 0
fi

# -- preflight ------------------------------------------------------------------------------------
echo "=== Preflight"
command -v docker >/dev/null || { echo "!!! docker is not installed."; exit 1; }
docker info >/dev/null 2>&1 || { echo "!!! Docker is not running, or this user cannot use it."; exit 1; }
command -v python3 >/dev/null || { echo "!!! python3 is needed."; exit 1; }
compose_version="$(docker compose version --short 2>/dev/null || true)"
python3 -I -c 'import sys; v=[int(x) for x in sys.argv[1].lstrip("v").split(".")[:2]]; sys.exit(0 if v>=[2,24] else 1)' \
  "${compose_version:-0.0}" 2>/dev/null || { echo "!!! Docker Compose 2.24 or later is needed (have '${compose_version:-none}')."; exit 1; }
echo "  docker compose $compose_version"

RUN_ID="$(date +%Y%m%d%H%M%S)-$(python3 -I -c 'import secrets; print(secrets.token_hex(3))')"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/cpintel-it.XXXXXX")"
chmod 700 "$WORK"
CREATED=()          # projects this run started, removed on exit unless --keep
BEFORE_VOLUMES="$(docker volume ls -q | sort)"

finish() {
  local status=$?
  if [ "$KEEP" = 1 ] && [ "${#CREATED[@]}" -gt 0 ]; then
    local last="${CREATED[-1]}"
    echo "Kept $last. Remove it with: scripts/integration-test.sh --cleanup --yes"
    for p in "${CREATED[@]}"; do [ "$p" = "$last" ] || remove_project "$p" || true; done
  else
    for p in "${CREATED[@]}"; do remove_project "$p" || true; done
  fi
  rm -rf "$WORK"
  exit "$status"
}
trap finish EXIT
trap 'exit 130' INT TERM

secret() { python3 -I -c 'import secrets; print(secrets.token_urlsafe(36))'; }
free_port() { python3 -I -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()'; }

# The run's settings. Everything a production .env needs, generated; nothing is ever echoed.
ENV_FILE="$WORK/it.env"
(
  umask 077
  cat > "$ENV_FILE" <<ENV
POSTGRES_USER=cpintel
POSTGRES_PASSWORD=$(secret)
MONGO_USER=cpintel_root
MONGO_PASSWORD=$(secret)
MONGO_APP_USER=cpintel_app
MONGO_APP_PASSWORD=$(secret)
REDIS_PASSWORD=$(secret)
JWT_SECRET=$(secret)
CPINTEL_SESSION_KEY=$(secret)
CPINTEL_DOMJUDGE_CREDENTIAL_KEY=$(secret)
CPINTEL_EXAM_PASSWORD_KEY=$(secret)
CPINTEL_RUNNER_TOKEN=$(secret)
CPINTEL_ADMIN_EMAIL=it-admin-$RUN_ID@cpintel.test
CPINTEL_ADMIN_USERNAME=it_admin
CPINTEL_ADMIN_PASSWORD=$(secret)
CPINTEL_DOMAIN=localhost
CPINTEL_PUBLIC_URL=https://localhost
GRAFANA_PASSWORD=$(secret)
ENV
)
env_value() { grep "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2-; }

# Anything that might be shown - a log tail, a failing command's output - goes through this.
redact() {
  python3 -I -c '
import sys
secrets = [l.split("=", 1)[1].strip() for l in open(sys.argv[1]) if "=" in l]
secrets = [s for s in secrets if len(s) >= 12]
for line in sys.stdin:
    for s in secrets:
        line = line.replace(s, "<redacted>")
    sys.stdout.write(line)' "$ENV_FILE"
}

PROJECT="" PORT=""
compose() {
  CPINTEL_IT_PREFIX="$PROJECT" CPINTEL_IT_HTTPS_PORT="$PORT" CPINTEL_IT_MONGO_INIT="$MONGO_INIT" \
    docker compose -p "$PROJECT" --env-file "$ENV_FILE" \
      -f "$ROOT/docker-compose.yml" -f "$HERE/docker-compose.it.yml" "$@"
}

diagnose() {   # what to read when something did not come up, without a secret in it
  echo "--- $PROJECT: containers"
  compose ps -a 2>&1 | redact || true
  for s in "$@"; do
    echo "--- $PROJECT: last lines of $s"
    docker logs --tail 60 "$PROJECT-$( [ "$s" = mongodb ] && echo mongo || echo "$s")" 2>&1 | redact || true
  done
}

health_of() { docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$1" 2>/dev/null || echo missing; }

wait_healthy() {   # <seconds> <service>...: every one reports healthy, or the run stops
  local limit="$1"; shift
  local s deadline=$((SECONDS + limit))
  for s in "$@"; do
    until [ "$(health_of "$PROJECT-$( [ "$s" = mongodb ] && echo mongo || echo "$s")")" = healthy ]; do
      [ $SECONDS -lt $deadline ] || { echo "!!! $s was not healthy within ${limit}s."; diagnose "$s"; return 1; }
      sleep 2
    done
  done
  echo "  healthy: $*"
}

# The backend's health through nginx: prints UP, DOWN, or what went wrong. Allowed 45 s: with
# MongoDB gone, the health check waits out the driver's 30 s server selection before it answers.
backend_health() {
  python3 -I -c '
import json, ssl, sys, urllib.request, urllib.error
ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
req = urllib.request.Request(sys.argv[1] + "/actuator/health", headers={"Host": "localhost"})
try:
    with urllib.request.urlopen(req, context=ctx, timeout=45) as r: body = r.read()
except urllib.error.HTTPError as e: body = e.read()
except Exception: print("unreachable"); sys.exit()
try: print(json.loads(body).get("status", "?"))
except ValueError: print("no-json")' "https://127.0.0.1:$PORT"
}

wait_backend() {   # <seconds> <UP|DOWN>
  local limit="$1" want="$2" deadline=$((SECONDS + $1)) now=""
  while [ $SECONDS -lt $deadline ]; do
    now="$(backend_health)"
    [ "$now" = "$want" ] && { echo "  backend health: $want"; return 0; }
    sleep 3
  done
  echo "!!! The backend's health was '$now', not $want, after ${limit}s."
  return 1
}

root_mongo() {   # <javascript>: as MongoDB's root account, inside the test container
  docker exec -i "$PROJECT-mongo" sh -c 'umask 077; f=$(mktemp); cat > "$f"; mongosh --quiet \
    -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin \
    cpintel --file "$f"; s=$?; rm -f "$f"; exit $s' <<<"$1"
}

app_account() {   # [args]: scripts/mongo-app-user.sh against this phase's MongoDB
  CPINTEL_MONGO_CONTAINER="$PROJECT-mongo" MONGO_APP_USER="$(env_value MONGO_APP_USER)" \
    MONGO_APP_PASSWORD="$(env_value MONGO_APP_PASSWORD)" "$ROOT/scripts/mongo-app-user.sh" "$@"
}

start_phase() {   # <project suffix> <mongo-init.js>
  PROJECT="cpintel-it-$RUN_ID$1"
  MONGO_INIT="$2"
  PORT="$(free_port)"
  echo "  project $PROJECT, nginx on https://127.0.0.1:$PORT"
  # The resolved configuration holds the run's secrets: kept in the private directory, read by
  # isolation.py (which prints none of it), and deleted.
  if ! compose config --format json > "$WORK/config.json" 2> "$WORK/config.err"; then
    echo "!!! Compose could not resolve the configuration:"; redact < "$WORK/config.err"; return 1
  fi
  python3 -I "$HERE/isolation.py" "$PROJECT" "$ROOT" < "$WORK/config.json"
  rm -f "$WORK/config.json" "$WORK/config.err"
  CREATED+=("$PROJECT")
}

build_images() {
  echo "  building backend, frontend and nginx (log in the run's temporary directory) ..."
  if ! compose build backend frontend nginx > "$WORK/build-$PROJECT.log" 2>&1; then
    echo "!!! The images did not build:"; tail -40 "$WORK/build-$PROJECT.log" | redact
    return 1
  fi
}

# No generated secret or reset link in any container's log. (The bootstrap admin's address is
# configuration the backend names when it creates that account, so it is not looked for.)
logs_clean() {
  local logs="$WORK/logs-$PROJECT.txt" hits
  local c
  for c in $(compose ps -aq 2>/dev/null); do docker logs "$c" >> "$logs" 2>&1 || true; done
  hits="$(python3 -I -c '
import sys
text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
env = dict(l.rstrip("\n").split("=", 1) for l in open(sys.argv[2]) if "=" in l)
# Account names are not secrets (MongoDB logs who signed in); everything else generated is.
skip = {"CPINTEL_PUBLIC_URL", "CPINTEL_ADMIN_EMAIL"}
found = [k for k, v in env.items() if len(v) >= 12 and v in text and k not in skip
         and not k.endswith(("_USER", "_USERNAME"))]
if "reset-password?token=" in text: found.append("a reset link")
print(", ".join(found))' "$logs" "$ENV_FILE")"
  rm -f "$logs"
  [ -z "$hits" ] || { echo "!!! Found in the containers' logs: $hits"; return 1; }
  echo "  no generated secret or reset link in any container's log"
}

# -- the missing-password refusal, before anything is built ----------------------------------------
echo "=== Configuration"
PROJECT="cpintel-it-$RUN_ID" PORT=1 MONGO_INIT="$ROOT/mongo-init.js"
grep -v '^MONGO_APP_PASSWORD=' "$ENV_FILE" > "$WORK/no-app-password.env"
if out="$(CPINTEL_IT_PREFIX="$PROJECT" CPINTEL_IT_HTTPS_PORT=1 CPINTEL_IT_MONGO_INIT="$MONGO_INIT" \
    docker compose -p "$PROJECT" --env-file "$WORK/no-app-password.env" -f "$ROOT/docker-compose.yml" \
    -f "$HERE/docker-compose.it.yml" config --quiet 2>&1)"; then
  echo "!!! Compose accepted a configuration without MONGO_APP_PASSWORD."; exit 1
fi
grep -q 'set MONGO_APP_PASSWORD in .env' <<<"$out" || { echo "!!! Without MONGO_APP_PASSWORD, Compose failed for another reason:"; redact <<<"$out"; exit 1; }
echo "  without MONGO_APP_PASSWORD, Compose refuses before starting anything"

api_checks() {   # fresh|upgrade
  IT_BASE_URL="https://127.0.0.1:$PORT" IT_ADMIN_EMAIL="$(env_value CPINTEL_ADMIN_EMAIL)" \
    IT_ADMIN_PASSWORD="$(env_value CPINTEL_ADMIN_PASSWORD)" python3 -I "$HERE/api_checks.py" "$1"
}

# -- fresh install --------------------------------------------------------------------------------
phase_fresh() {
  echo "=== Fresh install"
  start_phase "" "$ROOT/mongo-init.js"
  build_images
  compose up -d postgres mongodb redis >/dev/null 2>&1
  wait_healthy 180 postgres mongodb redis
  app_account --check | sed 's/^/  /'
  compose up -d backend frontend nginx >/dev/null 2>&1
  wait_backend 300 UP || { diagnose backend nginx; return 1; }
  # The backend creates its entities' indexes at start; mongo-init.js has none of these two.
  local made
  made="$(root_mongo 'print(db.personal_files.getIndexes().some(i => i.name === "idx_files_user_name" && i.unique) + "," + db.contest_file_rules.getIndexes().some(i => i.name === "idx_file_rule_contest" && i.unique))')"
  [ "$made" = "true,true" ] || { echo "!!! The backend did not create its unique indexes ($made)."; return 1; }
  echo "  the backend created its own unique indexes (personal files, contest file rules)"
  api_checks fresh || { diagnose backend nginx; return 1; }

  echo "  stopping MongoDB: health must say DOWN, not UP"
  compose stop mongodb >/dev/null 2>&1
  wait_backend 150 DOWN || { diagnose backend; return 1; }
  compose start mongodb >/dev/null 2>&1
  wait_healthy 120 mongodb
  wait_backend 120 UP || { diagnose backend; return 1; }
  logs_clean
}

# -- upgrade from the release before the backend's own account -------------------------------------
phase_upgrade() {
  echo "=== Upgrade"
  start_phase "-upgrade" "$HERE/fixtures/mongo-init-before-p6.js"
  [ "${#PHASES[@]}" -eq 2 ] || build_images
  [ "${#PHASES[@]}" -eq 2 ] && retag_images
  compose up -d postgres mongodb redis >/dev/null 2>&1
  wait_healthy 180 postgres mongodb redis

  # What a server installed before P6 holds: data written with the root account, and no app account.
  root_mongo '
db.code_submissions.insertMany([
  { userId: 990001, platform: "CODEFORCES", contestId: "1900", problemIndex: "A", externalId: "it-1", source: "int main(){}", submittedAt: new Date() },
  { userId: 990001, platform: "CODEFORCES", contestId: "1900", problemIndex: "B", externalId: "it-2", source: "int main(){return 0;}", submittedAt: new Date() }]);
db.cf_submissions.insertOne({ userId: 990001, cfSubmissionId: 424242, verdict: "OK", submittedAt: new Date() });' >/dev/null
  [ "$(root_mongo 'print(db.getUser("cpintel_app") === null)')" = true ] \
    || { echo "!!! The earlier release's database already has cpintel_app."; return 1; }
  echo "  an earlier release's database: no cpintel_app, 3 documents written as root"

  if app_account --check >/dev/null; then echo "!!! --check passed before the account existed."; return 1; fi
  app_account | sed 's/^/  /'
  app_account | sed 's/^/  (again) /'

  # The backend on the old, default-named indexes: it must refuse to come up, and say why.
  compose up -d backend >/dev/null 2>&1
  local deadline=$((SECONDS + 240))
  # grep -c, not -q: -q stops reading at the first match, compose logs dies of SIGPIPE, and
  # under pipefail the match would count as a miss.
  until [ "$(docker logs "$PROJECT-backend" 2>&1 | grep -c 'IndexOptionsConflict')" -gt 0 ]; do
    [ $SECONDS -lt $deadline ] || { echo "!!! The backend did not report IndexOptionsConflict."; diagnose backend; return 1; }
    [ "$(health_of "$PROJECT-backend")" = healthy ] && { echo "!!! The backend came up on conflicting indexes."; return 1; }
    sleep 3
  done
  compose stop backend >/dev/null 2>&1
  echo "  the backend refused to start on the old indexes, with IndexOptionsConflict in its log"

  # The README's remedy.
  root_mongo '
db.code_submissions.dropIndex("userId_1_platform_1_contestId_1_problemIndex_1_submittedAt_-1");
db.code_submissions.dropIndex("userId_1_platform_1_externalId_1");
db.cf_submissions.dropIndex("userId_1_cfSubmissionId_1");
db.cf_submissions.dropIndex("userId_1_verdict_1");' >/dev/null
  echo "  dropped the four default-named indexes, as the README says"

  compose up -d backend frontend nginx >/dev/null 2>&1
  wait_backend 300 UP || { diagnose backend nginx; return 1; }
  local counts
  counts="$(root_mongo 'print(db.code_submissions.countDocuments({ userId: 990001 }) + "," + db.cf_submissions.countDocuments({ userId: 990001 }) + "," + db.code_submissions.getIndexes().map(i => i.name).includes("idx_code_user_problem"))')"
  [ "$counts" = "2,1,true" ] || { echo "!!! After the upgrade: documents and index '$counts', expected 2,1,true."; return 1; }
  echo "  the data written before the upgrade is all there, and the backend made its own indexes"
  api_checks upgrade || { diagnose backend nginx; return 1; }
  app_account --check | sed 's/^/  /'
  logs_clean
}

# The upgrade phase reuses the images the fresh phase built rather than building them again.
retag_images() {
  local s
  for s in backend frontend nginx; do
    docker tag "cpintel-it-$RUN_ID-$s" "$PROJECT-$s"
  done
}

for phase in "${PHASES[@]}"; do
  "phase_$phase"
  # Each phase's stack goes once it has passed, except the one --keep keeps. Its images stay
  # until the end: the upgrade reuses them.
  if [ "$KEEP" = 0 ] || [ "$phase" != "${PHASES[-1]}" ]; then
    compose down -v --remove-orphans >/dev/null 2>&1
  fi
done

# -- nothing outside the run's own projects changed ---------------------------------------------------
if [ "$KEEP" = 0 ]; then
  for p in "${CREATED[@]}"; do remove_project "$p"; done
  CREATED=()
  after="$(docker volume ls -q | sort)"
  [ "$after" = "$BEFORE_VOLUMES" ] || { echo "!!! The volumes are not as they were:"; diff <(echo "$BEFORE_VOLUMES") <(echo "$after") || true; exit 1; }
  [ -z "$(leftover_projects | grep -F "$RUN_ID" || true)" ] || { echo "!!! Something of this run was left behind."; exit 1; }
  echo "=== Removed everything this run created; the volumes are as they were"
fi
echo "=== Integration test passed (${PHASES[*]})"
