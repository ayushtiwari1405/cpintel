#!/usr/bin/env bash
#
# Probes a DOMjudge instance for the API surface CPIntel's compete arena needs.
#
# Run this once the instance is up and reachable. It changes nothing on the judge —
# every call is a GET unless ALLOW_SUBMIT=true is set explicitly.
#
#   ./scripts/domjudge-probe.sh
#
# Env:
#   DJ_URL     base URL of the instance      (default http://localhost:12345)
#   DJ_USER    API account username          (needs admin for the on-behalf checks)
#   DJ_PASS    API account password
#   CID        contest id to probe against   (default: the first contest listed)
#   PID        problem id to probe against   (default: the first problem in CID)
#   TEAM       team id for the submit check  (default: the first team in CID)
#
# What matters in the output:
#   * GET /api/v4/user — names the account and the team its submissions land on, which is
#     what the submit-as-the-contestant model rests on
#   * which sample-testcase route answers 200 — the version differences live there
#   * whether /users or /accounts answers, which is what proves the account is admin
#   * which contest-wide reads (teams, submissions, judgements, scoreboard public=false) a
#     non-admin account is refused — those are the ones the shared contest cache depends on
#   * the OpenAPI path dump at the end, which is the authoritative list for THIS build
#
set -uo pipefail

DJ_URL="${DJ_URL:-http://localhost:12345}"
DJ_URL="${DJ_URL%/}"
DJ_USER="${DJ_USER:-admin}"
DJ_PASS="${DJ_PASS:-}"

API="$DJ_URL/api/v4"

ok()   { printf '  \033[32m%3s\033[0m  %s\n' "$1" "$2"; }
bad()  { printf '  \033[31m%3s\033[0m  %s\n' "$1" "$2"; }
warn() { printf '  \033[33m%3s\033[0m  %s\n' "$1" "$2"; }
head_() { printf '\n\033[1m%s\033[0m\n' "$1"; }

if [ -z "$DJ_PASS" ]; then
  echo "DJ_PASS is not set. Export the API account's password and re-run." >&2
  exit 1
fi

CURL=(curl -sS --max-time 20 -u "$DJ_USER:$DJ_PASS")

# Prints "<status> <first 200 bytes of body>" for one GET, and colours by status.
probe() {
  local label="$1" url="$2"
  local body status
  body="$(mktemp)"
  status="$("${CURL[@]}" -o "$body" -w '%{http_code}' "$url" 2>/dev/null)"
  local snippet
  snippet="$(head -c 200 "$body" | tr '\n' ' ' | tr -s ' ')"
  case "$status" in
    2*) ok  "$status" "$label" ;;
    4*) warn "$status" "$label  ${snippet:0:90}" ;;
    *)  bad "$status" "$label  ${snippet:0:90}" ;;
  esac
  rm -f "$body"
  [ "${status:0:1}" = "2" ]
}

# Same, but echoes the whole body so a caller can pull an id out of it.
fetch() {
  "${CURL[@]}" "$1" 2>/dev/null
}

# Pulls the first "id" out of a JSON array without needing jq.
first_id() {
  sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' <<<"$1" | head -1
}

head_ "Reachability and version  ($DJ_URL)"
probe "GET /api/v4/version"     "$API/version"
probe "GET /api/v4/info"        "$API/info"
probe "GET /api/version"        "$DJ_URL/api/version"

head_ "Account identity  (who these credentials are)"
# Under submit-as-the-contestant this is the single most important call: it names the account
# and, crucially, the team DOMjudge will attribute its submissions to. If team is null here,
# the account is not a team account and submitting as it will go nowhere.
probe "GET /api/v4/user"        "$API/user"
WHOAMI="$(fetch "$API/user")"
if [ -n "$WHOAMI" ]; then
  echo "       $(head -c 300 <<<"$WHOAMI" | tr -s ' \n')"
fi

head_ "Contests"
CONTESTS="$(fetch "$API/contests")"
if [ -z "$CONTESTS" ]; then
  bad "---" "no contests returned — check credentials and that a contest exists"
else
  ok "200" "GET /contests"
  CID="${CID:-$(first_id "$CONTESTS")}"
  echo "       using contest id: ${CID:-<none found>}"
fi

if [ -z "${CID:-}" ]; then
  echo
  echo "No contest id to probe with. Create a contest, or set CID=... and re-run." >&2
  exit 1
fi

head_ "Contest state and metadata  (cid=$CID)"
probe "GET /contests/$CID"                "$API/contests/$CID"
probe "GET /contests/$CID/state"          "$API/contests/$CID/state"
probe "GET /contests/$CID/problems"       "$API/contests/$CID/problems"
probe "GET /contests/$CID/languages"      "$API/contests/$CID/languages"
probe "GET /contests/$CID/teams"          "$API/contests/$CID/teams"
probe "GET /contests/$CID/judgement-types" "$API/contests/$CID/judgement-types"
probe "GET /contests/$CID/scoreboard"     "$API/contests/$CID/scoreboard?public=false"
# The code asks for public=false (the jury view, truthful through a freeze). A team account is
# likely refused it and must fall back to the public board — which freezes. Both are probed so
# the fallback is a measured fact rather than an assumption.
probe "GET /contests/$CID/scoreboard (public=true)" "$API/contests/$CID/scoreboard?public=true"

head_ "Submissions and judgements  (the verdict path)"
probe "GET /contests/$CID/submissions"    "$API/contests/$CID/submissions"
probe "GET /contests/$CID/judgements"     "$API/contests/$CID/judgements"
probe "GET /contests/$CID/runs"           "$API/contests/$CID/runs"
probe "GET /contests/$CID/event-feed"     "$API/contests/$CID/event-feed?stream=false"

head_ "Admin rights  (needed to submit on a team's behalf)"
probe "GET /users"                        "$API/users"
probe "GET /accounts"                     "$API/accounts"
probe "GET /contests/$CID/accounts"       "$API/contests/$CID/accounts"

PROBLEMS="$(fetch "$API/contests/$CID/problems")"
PID="${PID:-$(first_id "$PROBLEMS")}"
head_ "Statements  (pid=${PID:-<none>})"
if [ -n "${PID:-}" ]; then
  probe "GET /contests/$CID/problems/$PID"           "$API/contests/$CID/problems/$PID"
  # The API route, which exists only on newer DOMjudge. On 8.0 it 404s and the OpenAPI
  # document lists no statement route at all — which is what made statements look like a
  # missing problem package rather than a missing route.
  probe "GET /contests/$CID/problems/$PID/statement" "$API/contests/$CID/problems/$PID/statement"
  # The web routes, which are where 8.x actually publishes the text. /team needs a signed-in
  # session — the web firewall refuses HTTP Basic, so it answers 302 to /login here even with
  # good credentials, and a 302 on this line is normal rather than a fault. /public answers
  # only for problems in a contest the instance exposes publicly.
  probe "GET (team UI) /team/problems/$PID/text"     "$DJ_URL/team/problems/$PID/text"
  probe "GET (public)  /public/problems/$PID/text"   "$DJ_URL/public/problems/$PID/text"
  probe "GET  (public) /public/problems/$PID/statement" "$DJ_URL/public/problems/$PID/statement"
else
  warn "---" "no problems in this contest yet — add one and re-run for statement/sample checks"
fi

head_ "Sample testcases  —  the route that answers 200 is the one CPIntel will use"
if [ -n "${PID:-}" ]; then
  probe "GET /contests/$CID/problems/$PID/testcases"    "$API/contests/$CID/problems/$PID/testcases"
  probe "GET /problems/$PID/testcases"                  "$API/problems/$PID/testcases"
  probe "GET /contests/$CID/problems/$PID/samples"      "$API/contests/$CID/problems/$PID/samples"
  probe "GET /contests/$CID/problems/$PID/sample-data"  "$API/contests/$CID/problems/$PID/sample-data"
  probe "GET (team UI) /team/problems/$PID/samples.zip" "$DJ_URL/team/problems/$PID/samples.zip"
  probe "GET (public)  /public/problems/$PID/samples.zip" "$DJ_URL/public/problems/$PID/samples.zip"
  probe "GET (jury UI) /jury/problems/$PID/testcases"   "$DJ_URL/jury/problems/$PID/testcases"
fi

head_ "Submit on behalf of a team"
TEAMS="$(fetch "$API/contests/$CID/teams")"
TEAM="${TEAM:-$(first_id "$TEAMS")}"
echo "       first team id: ${TEAM:-<none found>}"
if [ "${ALLOW_SUBMIT:-false}" = "true" ] && [ -n "${TEAM:-}" ] && [ -n "${PID:-}" ]; then
  LANGS="$(fetch "$API/contests/$CID/languages")"
  LANG_ID="${LANG_ID:-$(first_id "$LANGS")}"
  SRC="$(mktemp /tmp/probe-XXXX.cpp)"
  printf 'int main(){return 0;}\n' > "$SRC"
  echo "       POST /contests/$CID/submissions  team=$TEAM problem=$PID language=$LANG_ID"
  "${CURL[@]}" -X POST \
    -F "problem=$PID" -F "language=$LANG_ID" -F "team_id=$TEAM" \
    -F "code[]=@$SRC" \
    -w '\n       -> HTTP %{http_code}\n' \
    "$API/contests/$CID/submissions"
  rm -f "$SRC"
else
  warn "---" "skipped — re-run with ALLOW_SUBMIT=true to actually test submit-on-behalf"
fi

head_ "Submit as this account itself  (no team_id — the per-contestant model)"
# Distinct from the on-behalf check above. A plain team account cannot pass team_id, but it can
# submit for its own team by omitting it entirely; DOMjudge infers the team from the login.
# This is what proves the per-contestant path works with the credentials in hand.
if [ "${ALLOW_SUBMIT:-false}" = "true" ] && [ -n "${PID:-}" ]; then
  LANGS="${LANGS:-$(fetch "$API/contests/$CID/languages")}"
  LANG_ID="${LANG_ID:-$(first_id "$LANGS")}"
  SRC="$(mktemp /tmp/probe-self-XXXX.cpp)"
  printf 'int main(){return 0;}\n' > "$SRC"
  echo "       POST /contests/$CID/submissions  problem=$PID language=$LANG_ID (no team_id)"
  "${CURL[@]}" -X POST \
    -F "problem=$PID" -F "language=$LANG_ID" \
    -F "code[]=@$SRC" \
    -w '\n       -> HTTP %{http_code}\n' \
    "$API/contests/$CID/submissions"
  rm -f "$SRC"
else
  warn "---" "skipped — re-run with ALLOW_SUBMIT=true to test submitting as this account"
fi

head_ "OpenAPI paths for this build  (authoritative — overrides everything guessed above)"
SPEC="$(fetch "$DJ_URL/api/doc.json")"
[ -z "$SPEC" ] && SPEC="$(fetch "$DJ_URL/api/doc?format=json")"
if [ -n "$SPEC" ]; then
  echo "$SPEC" \
    | tr ',' '\n' \
    | sed -n 's|.*"\(/contests[^"]*\)".*|  \1|p;s|.*"\(/problems[^"]*\)".*|  \1|p' \
    | sort -u | head -60
  echo
  echo "  paths mentioning testcase/sample/statement:"
  echo "$SPEC" | tr ',' '\n' | grep -oE '"/[^"]*(testcase|sample|statement)[^"]*"' \
    | sort -u | sed 's/^/    /'
else
  warn "---" "could not read /api/doc.json — open $DJ_URL/api/doc in a browser instead"
fi

echo
echo "Done. Paste this output back and the sample-testcase fetcher gets built against it."
