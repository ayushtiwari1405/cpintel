#!/usr/bin/env bash
# Smoke test against a running CPIntel: signs in, walks the main API surface, signs out.
#
#   E2E_USER=someone E2E_PASSWORD=... ./scripts/e2e-test.sh                     # dev backend
#   BASE=https://cpintel.example.edu E2E_USER=... E2E_PASSWORD=... ./scripts/e2e-test.sh
#
# Registration is closed, so the account must already exist — make a throwaway one in the admin
# console. BASE defaults to the development backend; point it at the server after a deploy (a
# temporary self-signed certificate is accepted, since that is what a fresh server serves).
set -uo pipefail

BASE="${BASE:-http://localhost:8080}"
API="$BASE/api/v1"
: "${E2E_USER:?set E2E_USER to the username or email of an existing account}"
: "${E2E_PASSWORD:?set E2E_PASSWORD}"

PASS=0
FAIL=0
ERRORS=()
JAR=$(mktemp)
trap 'rm -f "$JAR"' EXIT
CURL=(curl -sk --max-time 60 -b "$JAR" -c "$JAR")

check() {
  local desc="$1" method="$2" url="$3" body="${4:-}" auth="${5:-}" expect="${6:-200}"
  local args=(-o /dev/null -w "%{http_code}" -X "$method")
  [[ -n "$body" ]] && args+=(-H "Content-Type: application/json" -d "$body")
  [[ -n "$auth" ]] && args+=(-H "Authorization: Bearer $auth")
  local status
  status=$("${CURL[@]}" "${args[@]}" "$url")
  if [[ "$status" == "$expect" ]]; then
    echo "  ✓  $desc ($status)"; ((PASS++))
  else
    echo "  ✗  $desc (got $status, expected $expect)"; ((FAIL++)); ERRORS+=("$desc: got $status")
  fi
}

echo ""
echo "=== CPIntel smoke test: $BASE ==="
echo ""

echo "Edge:"
HEALTH_PATH=health; [[ "$BASE" == *:8080* ]] && HEALTH_PATH=actuator/health
check "GET  /$HEALTH_PATH" GET "$BASE/$HEALTH_PATH"

echo ""
echo "Signing in:"
LOGIN=$("${CURL[@]}" -X POST "$API/auth/login" -H "Content-Type: application/json" \
  -d "{\"identifier\":\"$E2E_USER\",\"password\":\"$E2E_PASSWORD\"}")
TOKEN=$(echo "$LOGIN" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null)
if [[ -n "$TOKEN" ]]; then
  echo "  ✓  POST /auth/login"; ((PASS++))
else
  echo "  ✗  POST /auth/login — $LOGIN"; ((FAIL++)); ERRORS+=("login failed")
  echo ""; echo "Cannot continue without a session."; exit 1
fi
if grep -q cpintel_refresh "$JAR"; then
  echo "  ✓  refresh token arrived as a cookie"; ((PASS++))
else
  echo "  ✗  no refresh cookie"; ((FAIL++)); ERRORS+=("no refresh cookie")
fi
check "POST /auth/refresh (cookie)"  POST "$API/auth/refresh"
check "POST /auth/login (bad pass)"  POST "$API/auth/login" \
  "{\"identifier\":\"$E2E_USER\",\"password\":\"definitely-wrong\"}" "" 401

echo ""
echo "Account and analytics:"
check "GET  /users/me"               GET  "$API/users/me"            "" "$TOKEN"
check "GET  /users/me (no token)"    GET  "$API/users/me"            "" ""       401
check "GET  /users/dashboard"        GET  "$API/users/dashboard"     "" "$TOKEN"
check "GET  /analytics/overview"     GET  "$API/analytics/overview"  "" "$TOKEN"
check "GET  /roadmaps/current"       GET  "$API/roadmaps/current"    "" "$TOKEN"
check "GET  /roadmaps/gauntlet"      GET  "$API/roadmaps/gauntlet"   "" "$TOKEN"

echo ""
echo "Practice, contests, examinations:"
check "GET  /practice/tags"          GET  "$API/practice/tags"       "" "$TOKEN"
check "GET  /practice/cf-session"    GET  "$API/practice/cf-session" "" "$TOKEN"
check "GET  /exams"                  GET  "$API/exams"               "" "$TOKEN"
check "GET  /groups/contests"        GET  "$API/groups/contests"     "" "$TOKEN"

echo ""
echo "Running code (the runner container on a server):"
check "GET  /run/status"             GET  "$API/run/status"          "" "$TOKEN"
RUN=$("${CURL[@]}" -X POST "$API/run" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"language":"cpp","source":"#include <iostream>\nint main(){int a,b;std::cin>>a>>b;std::cout<<a+b;}","tests":[{"input":"2 3","expected":"5","label":"t"}]}')
VERDICT=$(echo "$RUN" | python3 -c "import sys,json; d=json.load(sys.stdin)['data']; print(d['results'][0]['verdict'] if d.get('results') else d.get('error'))" 2>/dev/null)
if [[ "$VERDICT" == "OK" ]]; then
  echo "  ✓  C++ compiles and passes a sample"; ((PASS++))
else
  echo "  ✗  running code: $VERDICT"; ((FAIL++)); ERRORS+=("run: $VERDICT")
fi

echo ""
echo "Signing out:"
check "POST /auth/logout"            POST "$API/auth/logout"         "" "$TOKEN"

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Passed: $PASS"
echo "  Failed: $FAIL"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
if [[ $FAIL -gt 0 ]]; then
  echo ""; echo "Failures:"
  for e in "${ERRORS[@]}"; do echo "  - $e"; done
  exit 1
fi
