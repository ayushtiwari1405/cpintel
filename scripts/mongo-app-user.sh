#!/usr/bin/env bash
# Creates, or checks, the MongoDB account the backend connects with: readWrite on the cpintel
# database and nothing else. The root account (MONGO_USER) stays for administration.
#
#   scripts/mongo-app-user.sh                    create the account if it is missing, then check it
#   scripts/mongo-app-user.sh --check            only check it; exit non-zero if missing or wrong
#   scripts/mongo-app-user.sh --reset-password   also set its password to MONGO_APP_PASSWORD
#
# Why it exists: mongo-init.js creates this account, but MongoDB runs init scripts only on an
# empty data directory, so a database created before the account existed never gets it from
# there. deploy.sh and start.sh run this first, so the account is in place before the backend
# switches to it. Safe to run any number of times.
#
# What it does and does not do:
#   - It never changes an existing account's roles. If the account exists with roles other than
#     readWrite on cpintel, it says so and stops; deciding what that account is for is a person's
#     call. A password that does not match MONGO_APP_PASSWORD is changed only with
#     --reset-password.
#   - It writes nothing to the data. The check signs in as the account, reads its own
#     connectionStatus (user and roles), and confirms it is refused admin.system.users.
#   - No password reaches a command line or the output: the account's travels in this script's
#     environment and on stdin; the root one is the container's own, read by mongosh from the
#     container's environment inside the script it runs.
#   - It waits (up to a minute) for MongoDB to answer on the container's own network address.
#     On a first start the image runs mongod once on localhost only, to run mongo-init.js, then
#     again for real; an answer on localhost alone can come from that first run.
#
# Settings (environment, or .env beside docker-compose.yml):
#   MONGO_APP_USER            the account's name               default cpintel_app
#   MONGO_APP_PASSWORD        its password                     required
#   CPINTEL_MONGO_CONTAINER   the Mongo container              default cpintel-mongo
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE=ensure RESET=0
for arg in "$@"; do
  case "$arg" in
    --check) MODE=check ;;
    --reset-password) RESET=1 ;;
    *) echo "Unknown option: $arg (see the top of $0)"; exit 2 ;;
  esac
done

if [ -f "$ROOT/.env" ]; then
  # Only these settings, and without letting .env override what the caller exported.
  while IFS='=' read -r key value; do
    case "$key" in
      MONGO_APP_USER|MONGO_APP_PASSWORD|CPINTEL_MONGO_CONTAINER)
        [ -z "${!key:-}" ] && export "$key=$value" ;;
    esac
  done < <(grep -E '^(MONGO_APP_USER|MONGO_APP_PASSWORD|CPINTEL_MONGO_CONTAINER)=' "$ROOT/.env" || true)
fi
APP_USER="${MONGO_APP_USER:-cpintel_app}"
CONTAINER="${CPINTEL_MONGO_CONTAINER:-cpintel-mongo}"
[ -n "${MONGO_APP_PASSWORD:-}" ] || {
  echo "!!! MONGO_APP_PASSWORD is not set. Add it to .env (openssl rand -hex 24) and run this again."
  exit 1
}
[[ "$APP_USER" =~ ^[A-Za-z0-9_.-]+$ ]] || { echo "!!! MONGO_APP_USER may hold letters, digits, . _ and - only."; exit 1; }

# -- preflight ----------------------------------------------------------------------------------
[ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" = "true" ] \
  || { echo "!!! Container $CONTAINER is not running."; exit 1; }
# Runs the JavaScript on stdin as the container's root account. Fed to mongosh as a file rather
# than on stdin, where mongosh echoes its prompt into the output; the file is private to the
# container's root and removed at once, and keeps the account's password off any command line.
# Signs in from inside the script mongosh runs, with the root credentials the container already
# holds in its environment (MONGO_INITDB_ROOT_*): no password on a command line, on this machine
# or in the container, where the host's process list would show it.
# With "network", through the container's own address (HOSTNAME) rather than localhost.
root_mongosh() {
  local host='"localhost"'
  [ "${1:-}" = network ] && host='process.env.HOSTNAME'
  { printf 'db = connect("mongodb://" + encodeURIComponent(process.env.MONGO_INITDB_ROOT_USERNAME) + ":" + encodeURIComponent(process.env.MONGO_INITDB_ROOT_PASSWORD) + "@" + %s + ":27017/admin?authSource=admin");\n' "$host"
    cat; } | docker exec -i "$CONTAINER" sh -c 'umask 077; f=$(mktemp); cat > "$f"; \
    mongosh --quiet --nodb --file "$f"; status=$?; rm -f "$f"; exit $status'
}
# A container started a moment ago may not be answering yet, or may still be in its first,
# localhost-only start (see the top).
ready() { echo 'print(db.adminCommand({ ping: 1 }).ok)' | root_mongosh network 2>/dev/null | grep -qx 1; }
for _ in $(seq 1 60); do
  ready && break
  sleep 1
done
ready || { echo "!!! Could not sign in to $CONTAINER as its root account."; exit 1; }

# -- create or check ------------------------------------------------------------------------------
# The script travels on stdin; json.dumps quotes the values for JavaScript. The value goes to
# Python through its environment, not its arguments, which any local user can read.
js_string() { JS_VALUE="$1" python3 -I -c 'import json,os; print(json.dumps(os.environ["JS_VALUE"]))'; }
result="$(root_mongosh <<JS
const user = $(js_string "$APP_USER");
const password = $(js_string "$MONGO_APP_PASSWORD");
const mode = $(js_string "$MODE");
const reset = $RESET === 1;
const app = db.getSiblingDB('cpintel');
const existing = app.getUser(user);
const wanted = [{ role: 'readWrite', db: 'cpintel' }];
const same = r => r.length === 1 && r[0].role === 'readWrite' && r[0].db === 'cpintel';

if (!existing) {
  if (mode === 'check') { print('RESULT MISSING'); quit(0); }
  app.createUser({ user, pwd: password, roles: wanted });
  print('RESULT CREATED');
} else if (!same(existing.roles)) {
  print('RESULT WRONG_ROLES ' + JSON.stringify(existing.roles));
  quit(0);
} else if (reset) {
  app.changeUserPassword(user, password);
  print('RESULT PASSWORD_SET');
} else {
  print('RESULT EXISTS');
}

// Sign in as the account itself and ask what it is.
// A wrong password can surface on connecting or on the first command, so both are inside.
let conn, status;
try {
  conn = new Mongo('mongodb://' + encodeURIComponent(user) + ':' + encodeURIComponent(password)
    + '@localhost:27017/cpintel?authSource=cpintel');
  status = conn.getDB('cpintel').runCommand({ connectionStatus: 1 }).authInfo;
} catch (e) {
  print('CHECK LOGIN_FAILED');
  quit(0);
}
const asUser = status.authenticatedUsers.map(u => u.user + '@' + u.db).join(',');
const asRoles = status.authenticatedUserRoles.map(r => r.role + '@' + r.db).sort().join(',');
let refused = false;
try { conn.getDB('admin').getCollection('system.users').findOne(); } catch (e) { refused = /not authorized|Unauthorized/i.test(String(e)); }
print('CHECK USER ' + asUser);
print('CHECK ROLES ' + asRoles);
print('CHECK ADMIN_REFUSED ' + refused);
JS
)"

# The rest of the first line that starts with $1.
line() { printf '%s\n' "$result" | sed -n "s/^$1//p" | head -1; }
outcome="$(line 'RESULT ')"
case "$outcome" in
  MISSING) echo "!!! $APP_USER does not exist in cpintel. Run without --check to create it."; exit 1 ;;
  WRONG_ROLES*) echo "!!! $APP_USER exists with roles ${outcome#WRONG_ROLES } - not readWrite on cpintel."
                echo "    Not changed. Decide what that account is for, then fix or drop it by hand."; exit 1 ;;
  CREATED) echo "Created $APP_USER with readWrite on cpintel." ;;
  PASSWORD_SET) echo "Set the password of $APP_USER." ;;
  EXISTS) echo "$APP_USER already exists with readWrite on cpintel." ;;
  *) echo "!!! Unexpected answer from $CONTAINER."; exit 1 ;;
esac
if printf '%s\n' "$result" | grep -q '^CHECK LOGIN_FAILED'; then
  echo "!!! Could not sign in as $APP_USER with MONGO_APP_PASSWORD - the account's password differs."
  echo "    Run with --reset-password to set it, or put the account's real password in .env."
  exit 1
fi
[ "$(line 'CHECK USER ')" = "$APP_USER@cpintel" ] || { echo "!!! Signed in as '$(line 'CHECK USER ')', not $APP_USER@cpintel."; exit 1; }
[ "$(line 'CHECK ROLES ')" = "readWrite@cpintel" ] || { echo "!!! $APP_USER has roles '$(line 'CHECK ROLES ')'."; exit 1; }
[ "$(line 'CHECK ADMIN_REFUSED ')" = "true" ] || { echo "!!! $APP_USER was not refused admin.system.users."; exit 1; }
echo "Checked: signs in as $APP_USER@cpintel, roles readWrite@cpintel only, refused admin data."
