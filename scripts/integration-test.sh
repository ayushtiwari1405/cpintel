#!/usr/bin/env bash
# Opt-in full-stack integration test: the production images (built from this checkout) under a
# throwaway Compose project, through a fresh install and an upgrade from the previous release.
# Not part of CI or ci-local.sh - it builds every image and takes several minutes.
#
#   scripts/integration-test.sh                  both phases, then remove everything they made
#   scripts/integration-test.sh --only fresh     one phase (fresh | upgrade)
#   scripts/integration-test.sh --keep           leave the last phase's stack up (prints how to stop it)
#   scripts/integration-test.sh --cleanup        list what earlier runs left behind (removes nothing)
#   scripts/integration-test.sh --cleanup --yes  remove it - except a run still going (below)
#   scripts/integration-test.sh --cleanup --yes --project cpintel-it-<id>   only that project
#
# Fresh install: postgres, mongodb (with today's mongo-init.js), redis, backend, frontend and
# nginx. Checks the backend's MongoDB account is readWrite on cpintel only and that the backend
# created its own unique indexes, runs the API
# workflow in integration/api_checks.py through nginx, stops MongoDB, Redis and Postgres in turn
# and expects health to say DOWN within 20 s (each answer within 10 s, no details for an
# anonymous caller) and UP once each is back, and checks that no generated secret or reset link
# reached any container's log or command line.
#
# Upgrade: MongoDB starts from the previous release's mongo-init.js (integration/fixtures/), as
# a server installed before P6 would have it, with data written as root - among it duplicate
# personal file names and a duplicated contest rule, which nothing enforced before. Then the
# documented upgrade (README, "Upgrading an existing MongoDB"): scripts/mongo-app-user.sh
# creates the account (and finds nothing to do a second time); the backend, started on the old
# indexes, must stop rather than come up; scripts/mongo-index-upgrade.sh must find every
# conflict without changing anything, refuse to apply without a backup and while duplicates
# remain, export them, rename the duplicate files keeping every file's content, and - once a
# person has chosen between the duplicated rules - apply, and find nothing to do a second time.
# Then the backend must come up, every document must still be there, and the API must work.
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
#   - Cleanup works by project name, which must be cpintel-it-<letters, digits, dashes>: the
#     containers, volumes and networks carrying that Compose project label, and the images named
#     <project>-backend|frontend|nginx|runner (Compose labels its images with no project). Never
#     a prune. A run removes its own projects when it ends; --cleanup removes those left by runs
#     that did not end, but skips a project whose run still holds its lock - a flock on
#     ${CPINTEL_IT_LOCK_DIR:-$XDG_RUNTIME_DIR or /tmp}/cpintel-it-locks/<run>.lock, taken for the
#     run's whole life. The Docker build cache is kept.
#
# Needs: Docker with Compose v2.24 or later (!reset/!override), and python3. Time: a first run
# builds the backend, frontend and nginx images (several minutes); later runs reuse the build
# cache.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HERE="$ROOT/scripts/integration"
PHASES=(fresh upgrade) KEEP=0 CLEANUP=0 YES=0 ONLY_PROJECT=""
LOCK_DIR="${CPINTEL_IT_LOCK_DIR:-${XDG_RUNTIME_DIR:-/tmp}/cpintel-it-locks}"

while [ $# -gt 0 ]; do
  case "$1" in
    --only) shift; case "${1:-}" in fresh|upgrade) PHASES=("$1") ;; *) echo "--only fresh|upgrade"; exit 2 ;; esac ;;
    --keep) KEEP=1 ;;
    --cleanup) CLEANUP=1 ;;
    --yes) YES=1 ;;
    --project) shift; ONLY_PROJECT="${1:-}" ;;
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
# A run holds a flock on its lock file for as long as it lives; one that can be taken here belongs
# to a run that has ended.
lock_file() { echo "$LOCK_DIR/${1%-upgrade}.lock"; }
in_use() { [ -f "$(lock_file "$1")" ] && ! flock -n "$(lock_file "$1")" true; }

if [ "$CLEANUP" = 1 ]; then
  mapfile -t projects < <(leftover_projects | { if [ -n "$ONLY_PROJECT" ]; then grep -Fx -- "$ONLY_PROJECT" || true; else cat; fi; })
  [ "${#projects[@]}" -gt 0 ] || { echo "Nothing left behind by earlier runs."; exit 0; }
  echo "Left behind by earlier runs:"; printf '  %s\n' "${projects[@]}"
  [ "$YES" = 1 ] || { echo "Run again with --cleanup --yes to remove them."; exit 0; }
  command -v flock >/dev/null || { echo "!!! flock is needed to tell a running test from leftovers; nothing removed."; exit 1; }
  for p in "${projects[@]}"; do
    if in_use "$p"; then echo "Skipped $p: its run is still going."; continue; fi
    remove_project "$p" && rm -f "$(lock_file "$p")" && echo "Removed $p."
  done
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
command -v flock >/dev/null || { echo "!!! flock (util-linux) is needed."; exit 1; }
( umask 077; mkdir -p "$LOCK_DIR" )
LOCK="$(lock_file "cpintel-it-$RUN_ID")"
exec {LOCK_FD}>"$LOCK"
flock -n "$LOCK_FD" || { echo "!!! Could not lock $LOCK."; exit 1; }
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
  [ "$KEEP" = 1 ] || rm -f "$LOCK"
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
try:
    doc = json.loads(body)
    # Details are for a signed-in super admin; an anonymous caller sees the status alone, UP or DOWN.
    print(doc.get("status", "?") + ("+details" if "components" in doc or "details" in doc else ""))
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

# One dependency stopped and started again: health must say DOWN - within <bound> seconds, and
# each health answer within <answer bound> - then UP once it is back. Prints the times.
outage() {   # <service> <bound s> <answer bound s>
  local service="$1" bound="$2" answer_bound="$3" started now t0 took=0 slowest=0
  compose stop "$service" >/dev/null 2>&1
  started=$SECONDS
  while :; do
    t0=$SECONDS; now="$(backend_health)"; took=$((SECONDS - t0))
    [ "$took" -gt "$slowest" ] && slowest=$took
    [ "$now" = DOWN ] && break
    [ $((SECONDS - started)) -lt 150 ] || { echo "!!! With $service stopped, health was '$now' after 150 s."; diagnose backend; return 1; }
    sleep 1
  done
  echo "  $service stopped: health DOWN after $((SECONDS - started)) s; slowest health answer ${slowest} s"
  [ "$bound" = 0 ] || [ $((SECONDS - started)) -le "$bound" ] || { echo "!!! Not DOWN within ${bound} s."; return 1; }
  [ "$answer_bound" = 0 ] || [ "$slowest" -le "$answer_bound" ] || { echo "!!! A health answer took ${slowest} s (allowed ${answer_bound} s)."; return 1; }
  compose start "$service" >/dev/null 2>&1
  wait_healthy 120 "$service"
  started=$SECONDS
  wait_backend 120 UP >/dev/null || { echo "!!! Health did not return to UP after $service came back."; diagnose backend; return 1; }
  echo "  $service back: health UP after $((SECONDS - started)) s"
}

root_mongo() {   # <javascript>: as MongoDB's root account, inside the test container
  # The credentials are read by mongosh from the container's environment, as in
  # scripts/mongo-app-user.sh: no password on a command line.
  { printf '%s\n' 'db = connect("mongodb://" + encodeURIComponent(process.env.MONGO_INITDB_ROOT_USERNAME) + ":" + encodeURIComponent(process.env.MONGO_INITDB_ROOT_PASSWORD) + "@localhost:27017/cpintel?authSource=admin");'
    printf '%s\n' "$1"; } | docker exec -i "$PROJECT-mongo" sh -c 'umask 077; f=$(mktemp); cat > "$f"; \
    mongosh --quiet --nodb --file "$f"; s=$?; rm -f "$f"; exit $s'
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

# Every document and index of the test database, hashed: to show a step changed nothing.
db_digest() {
  root_mongo 'const out = {}; for (const c of db.getCollectionNames().sort()) out[c] = { d: db[c].find().sort({ _id: 1 }).toArray(), i: db[c].getIndexes() }; print(EJSON.stringify(out));' | sha256sum
}
# Every personal file but its name: what a rename must leave as it was.
file_digest() {
  root_mongo 'print(EJSON.stringify(db.personal_files.find({}, { name: 0 }).sort({ _id: 1 }).toArray(), { relaxed: false }))' | sha256sum
}
# No generated secret on any of the run's command lines: each container's process list
# (docker top) and the command and health check docker inspect shows. Secrets belong in the
# environment only. (Account names are not secrets.)
command_lines_clean() {
  local c found=0
  for c in $(compose ps -q 2>/dev/null); do
    { docker top "$c" -eo args 2>/dev/null || docker top "$c" 2>/dev/null
      docker inspect -f '{{json .Config.Cmd}} {{json .Config.Entrypoint}} {{json .Config.Healthcheck}}' "$c"
    } > "$WORK/cmdlines.txt"
    if ! python3 -I -c '
import sys
text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
env = dict(l.rstrip("\n").split("=", 1) for l in open(sys.argv[2]) if "=" in l)
hits = [k for k, v in env.items() if len(v) >= 12 and v in text and not k.endswith(("_USER", "_USERNAME", "_EMAIL", "_URL", "_DOMAIN"))]
print(", ".join(hits)); sys.exit(1 if hits else 0)' "$WORK/cmdlines.txt" "$ENV_FILE" > "$WORK/cmdlines.hits"; then
      echo "!!! On a command line of $(docker inspect -f '{{.Name}}' "$c"): $(cat "$WORK/cmdlines.hits")"; found=1
    fi
  done
  rm -f "$WORK/cmdlines.txt" "$WORK/cmdlines.hits"
  [ "$found" = 0 ] || return 1
  echo "  no generated secret on any container's command line, process list or health check"
}

# A dump by scripts/backup.sh, run from a copy in the run's directory so that no .env of this
# checkout is read - it could name a real backup directory or a remote to copy to.
take_backup() {
  mkdir -p "$WORK/backup-tool/scripts" "$WORK/backups"
  cp "$ROOT/scripts/backup.sh" "$WORK/backup-tool/scripts/"
  CPINTEL_BACKUP_DIR="$WORK/backups" CPINTEL_BACKUP_REMOTE="" CPINTEL_PG_CONTAINER="$PROJECT-postgres" \
    CPINTEL_MONGO_CONTAINER="$PROJECT-mongo" bash "$WORK/backup-tool/scripts/backup.sh" it >/dev/null 2>&1
  find "$WORK/backups" -name 'mongo-*.archive.gz' | head -1
}

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
  command_lines_clean
  api_checks fresh || { diagnose backend nginx; return 1; }

  # Each datastore stopped in turn: health must say DOWN, not UP, within 20 s - every answer
  # within 10 s, Docker's health check timeout - and UP once it is back.
  outage mongodb "${IT_HEALTH_DOWN_BOUND:-20}" "${IT_HEALTH_ANSWER_BOUND:-10}"
  outage redis "${IT_HEALTH_DOWN_BOUND:-20}" "${IT_HEALTH_ANSWER_BOUND:-10}"
  outage postgres "${IT_HEALTH_DOWN_BOUND:-20}" "${IT_HEALTH_ANSWER_BOUND:-10}"
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

  # What a server installed before P6 holds: data written with the root account, no app account,
  # and duplicates nothing enforced - personal file names (with extensions and an existing
  # "(2)") and a contest rule.
  root_mongo '
const t = i => new Date(Date.UTC(2026, 8, 1, 0, i));
const file = (u, name, i) => ({ userId: NumberLong(String(u)), name: name, label: "it-" + i, contentType: "text/plain",
  content: BinData(0, btoa("file " + i)), sizeBytes: 6, sourceHash: "h" + i, textual: true, uploadedAt: t(i), updatedAt: t(i) });
db.code_submissions.insertMany([
  { userId: NumberLong("990001"), platform: "CODEFORCES", contestId: "1900", problemIndex: "A", externalId: "it-1", source: "int main(){}", submittedAt: t(0) },
  { userId: NumberLong("990001"), platform: "CODEFORCES", contestId: "1900", problemIndex: "B", externalId: "it-2", source: "int main(){return 0;}", submittedAt: t(1) }]);
db.cf_submissions.insertOne({ userId: NumberLong("990001"), cfSubmissionId: NumberLong("424242"), verdict: "OK", submittedAt: t(0) });
db.personal_files.insertMany([file(990001, "main.cpp", 1), file(990001, "main.cpp", 2), file(990001, "main (2).cpp", 3),
  file(990001, "a.tar.gz", 4), file(990001, "a.tar.gz", 5), file(990002, "main.cpp", 6)]);
db.contest_file_rules.insertMany([
  { platform: "DOMJUDGE", contestId: "mid", enabled: false, updatedBy: NumberLong("1"), updatedAt: t(1) },
  { platform: "DOMJUDGE", contestId: "mid", enabled: true, updatedBy: NumberLong("2"), updatedAt: t(2) }]);' >/dev/null
  # The earlier release's unique (userId, cfSubmissionId) index already refuses a duplicate.
  [ "$(root_mongo 'try { db.cf_submissions.insertOne({ userId: NumberLong("990001"), cfSubmissionId: NumberLong("424242") }); print("accepted") } catch (e) { print(e.code) }')" = 11000 ] \
    || { echo "!!! The earlier release's cf_submissions index did not refuse a duplicate."; return 1; }
  [ "$(root_mongo 'print(db.getUser("cpintel_app") === null)')" = true ] \
    || { echo "!!! The earlier release's database already has cpintel_app."; return 1; }
  echo "  an earlier release's database: no cpintel_app, duplicate file names and a duplicated contest rule"
  local files_before
  files_before="$(file_digest)"

  if app_account --check >/dev/null; then echo "!!! --check passed before the account existed."; return 1; fi
  app_account | sed 's/^/  /'
  app_account | sed 's/^/  (again) /'

  # The backend on the old indexes: it must stop rather than come up. A protective stop, not the
  # migration.
  compose up -d backend >/dev/null 2>&1
  local deadline=$((SECONDS + 240))
  # grep -c, not -q: -q stops reading at the first match, the writer dies of SIGPIPE, and under
  # pipefail the match would count as a miss.
  until [ "$(docker logs "$PROJECT-backend" 2>&1 | grep -cE 'IndexOptionsConflict|E11000')" -gt 0 ]; do
    [ $SECONDS -lt $deadline ] || { echo "!!! The backend did not stop on the old indexes."; diagnose backend; return 1; }
    [ "$(health_of "$PROJECT-backend")" = healthy ] && { echo "!!! The backend came up on conflicting indexes."; return 1; }
    sleep 3
  done
  compose stop backend >/dev/null 2>&1
  echo "  the backend refused to start on the old indexes and duplicates"

  # The procedure, as the README gives it.
  local upgrade=("$ROOT/scripts/mongo-index-upgrade.sh" --container "$PROJECT-mongo") out state code
  state="$(db_digest)"
  set +e; out="$("${upgrade[@]}" --check 2>&1)"; code=$?; set -e
  [ "$code" = 3 ] || { echo "!!! --check answered $code, not 3 (duplicates):"; redact <<<"$out"; return 1; }
  if ! { grep -q 'personal_files (userId, name; idx_files_user_name): 2 duplicated key(s), 4 documents' <<<"$out" \
      && grep -q 'contest_file_rules (platform, contestId; idx_file_rule_contest): 1 duplicated key(s), 2 documents' <<<"$out" \
      && grep -q 'held as legacy userId_1_cfSubmissionId_1' <<<"$out"; }; then
    echo "!!! --check did not report every conflict:"; redact <<<"$out"; return 1
  fi
  [ "$(db_digest)" = "$state" ] || { echo "!!! --check changed the database."; return 1; }
  echo "  --check: duplicate files and rules found, legacy indexes recognised, nothing changed"

  set +e; "${upgrade[@]}" --apply >/dev/null 2>&1; code=$?; set -e
  [ "$code" = 4 ] || { echo "!!! --apply without a backup answered $code, not 4."; return 1; }
  local backup
  backup="$(take_backup)"
  set +e; "${upgrade[@]}" --apply --backup "$backup" >/dev/null 2>&1; code=$?; set -e
  [ "$code" = 3 ] && [ "$(db_digest)" = "$state" ] || { echo "!!! --apply did not refuse while duplicates remain ($code)."; return 1; }
  echo "  --apply refused without a backup, then while duplicates remain; nothing changed"

  "${upgrade[@]}" --export "$WORK/upgrade" >/dev/null 2>&1 || true
  local rules
  rules="$(cat "$WORK"/upgrade/mongo-index-upgrade-*/contest_file_rules.conflicts.jsonl)"
  if [ "$(grep -c . <<<"$rules")" != 2 ] || grep -q '"content"' "$WORK"/upgrade/mongo-index-upgrade-*/personal_files.conflicts.jsonl; then
    echo "!!! The export is not the two rules and the files without content."; return 1
  fi
  "${upgrade[@]}" --rename-duplicate-files "$WORK/upgrade" --confirm --backup "$backup" | grep -E '^Plan:|renamed [0-9]' | sed 's/^/  /'
  # The person's decision, by _id from the export: the first of the two rules goes.
  local drop
  # shellcheck disable=SC2016  # $oid is Extended JSON, not a shell variable
  drop="$(head -1 <<<"$rules" | python3 -I -c 'import json, sys; print(json.load(sys.stdin)["_id"]["$oid"])')"
  root_mongo "db.contest_file_rules.deleteOne({ _id: ObjectId(\"$drop\") })" >/dev/null
  echo "  exported both rules, renamed the duplicate files, removed the rule a person chose"

  set +e; out="$("${upgrade[@]}" --apply --backup "$backup" 2>&1)"; code=$?; set -e
  [ "$code" = 0 ] || { echo "!!! --apply answered $code:"; redact <<<"$out"; return 1; }
  state="$(db_digest)"
  set +e; out="$("${upgrade[@]}" --apply --backup "$backup" 2>&1)"; code=$?; set -e
  if [ "$code" != 0 ] || [ "$(db_digest)" != "$state" ] || grep -qE ' (created|dropped) ' <<<"$out"; then
    echo "!!! A second --apply was not a no-op ($code)."; return 1
  fi
  echo "  --apply put every index in place; a second run changed nothing"

  [ "$(file_digest)" = "$files_before" ] || { echo "!!! A personal file's content or metadata changed."; return 1; }
  # shellcheck disable=SC2016  # MongoDB field paths, not shell variables
  [ "$(root_mongo 'print(db.personal_files.aggregate([{ $group: { _id: { u: "$userId", n: "$name" }, c: { $sum: 1 } } }, { $match: { c: { $gt: 1 } } }]).toArray().length)')" = 0 ] \
    || { echo "!!! A user still has two files of one name."; return 1; }
  echo "  every personal file kept its content and metadata, under a name of its own"

  compose up -d backend frontend nginx >/dev/null 2>&1
  wait_backend 300 UP || { diagnose backend nginx; return 1; }
  local counts
  counts="$(root_mongo 'print([db.code_submissions.countDocuments({ userId: 990001 }), db.cf_submissions.countDocuments({ userId: 990001 }), db.personal_files.countDocuments(), db.contest_file_rules.countDocuments(), db.personal_files.getIndexes().some(i => i.name === "idx_files_user_name" && i.unique)].join(","))')"
  [ "$counts" = "2,1,6,1,true" ] || { echo "!!! After the upgrade: '$counts', expected 2,1,6,1,true."; return 1; }
  echo "  the backend is up; every document is there but the rule a person removed"
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
