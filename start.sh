#!/bin/bash
# Starts CPIntel from the tree this script lives in (not a hardcoded path).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

# Keep the compose project name fixed so the existing cpintel_* volumes and
# cpintel-* containers are reused no matter which copy of the tree we run from.
export COMPOSE_PROJECT_NAME=cpintel

# Load .env for the backend too.
#
# docker compose reads .env by itself, so the containers were always configured; the backend
# runs on the *host* and was not. Without this it falls back to application.yml's defaults,
# where POSTGRES_PORT is 5432 - but compose publishes Postgres on 5433, so the backend would
# connect to whatever else happens to be on 5432 and die with "password authentication failed
# for user cpintel". Already-exported variables win, matching how compose treats .env.
if [ -f "$ROOT/.env" ]; then
  while IFS= read -r line; do
    case "$line" in ''|'#'*) continue ;; esac
    key=${line%%=*}
    [ -z "${!key+x}" ] && export "$line"
  done < "$ROOT/.env"
fi

echo "Project root: $ROOT"
echo ""

# Free the app ports so a stale backend/frontend/helper can't shadow this run.
for port in 8080 5173 7717; do
  pids=$(lsof -ti "tcp:$port" -sTCP:LISTEN 2>/dev/null)
  if [ -n "$pids" ]; then
    echo "Port $port busy (pid $(echo "$pids" | tr '\n' ' ')) - stopping old process"
    kill $pids 2>/dev/null
    sleep 2
    pids=$(lsof -ti "tcp:$port" -sTCP:LISTEN 2>/dev/null)
    [ -n "$pids" ] && kill -9 $pids 2>/dev/null
  fi
done

# Start databases
docker compose up postgres mongodb redis -d || exit 1

echo "Waiting for Postgres..."
for _ in $(seq 1 60); do
  status=$(docker inspect -f '{{.State.Health.Status}}' cpintel-postgres 2>/dev/null)
  [ "$status" = "healthy" ] && break
  sleep 2
done
if [ "${status:-}" != "healthy" ]; then
  echo "Postgres did not become healthy - check: docker logs cpintel-postgres"
  exit 1
fi
echo "Postgres ready."

# Start backend
nohup bash -c "cd '$ROOT/backend' && ./mvnw spring-boot:run" > /tmp/backend.log 2>&1 &
disown
echo "Backend starting (log: /tmp/backend.log)..."
for _ in $(seq 1 90); do
  curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1 && break
  sleep 2
done
if curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; then
  echo "Backend up."
else
  echo "Backend not answering yet - tail /tmp/backend.log"
fi

# Start the Codeforces cookie helper.
#
# The "Connect Codeforces" button talks to this on 127.0.0.1:7717. Without it running, the
# card in the UI can only tell the user to start it by hand — so it belongs here, next to
# everything else the app needs. It reads Codeforces cookies out of the local browser
# profile and posts the session straight to the backend; the cookie never reaches the page.
HELPER="$ROOT/scripts/grab-cf-cookie.py"
if [ ! -f "$HELPER" ]; then
  echo "CF helper not found at $HELPER - skipping."
elif ! command -v python3 >/dev/null 2>&1; then
  echo "python3 not found - CF helper skipped; connect Codeforces by hand."
else
  nohup python3 "$HELPER" --serve --port 7717 > /tmp/cf-helper.log 2>&1 &
  disown
  echo "CF helper starting (log: /tmp/cf-helper.log)..."
  for _ in $(seq 1 15); do
    curl -sf --max-time 3 http://127.0.0.1:7717/health >/dev/null 2>&1 && break
    sleep 1
  done
  if curl -sf --max-time 3 http://127.0.0.1:7717/health >/dev/null 2>&1; then
    echo "CF helper up."
  else
    # Not fatal: everything except one-click Connect still works, and the UI falls back to
    # the manual Cookie-header paste. Chrome-family cookies usually need a keyring unlock.
    echo "CF helper not answering - see /tmp/cf-helper.log (Chrome cookies may need a keyring unlock)."
  fi
fi

# Start frontend.
# Via npm, not `npx vite`, so the predev hook runs and public/mathjax is populated —
# statements are raw $$$ markup without it.
nohup bash -c "cd '$ROOT/frontend' && npm run dev -- --port 5173" > /tmp/frontend.log 2>&1 &
disown
echo "Frontend starting (log: /tmp/frontend.log)..."
for _ in $(seq 1 30); do
  curl -sf http://localhost:5173 >/dev/null 2>&1 && break
  sleep 1
done

echo ""
echo "CPIntel is running from: $ROOT"
echo "  Frontend: http://localhost:5173"
echo "  Backend:  http://localhost:8080"
echo "  Swagger:  http://localhost:8080/swagger-ui.html"
echo "  CF helper: http://127.0.0.1:7717 (log: /tmp/cf-helper.log)"
