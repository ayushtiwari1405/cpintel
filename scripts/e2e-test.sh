#!/bin/bash
set -e

BASE="http://localhost:8080"
PASS=0
FAIL=0
ERRORS=()

check() {
  local desc="$1"
  local method="$2"
  local url="$3"
  local body="$4"
  local auth="$5"
  local expect="${6:-200}"

  local args=(-s -o /dev/null -w "%{http_code}" -X "$method")
  [[ -n "$body" ]] && args+=(-H "Content-Type: application/json" -d "$body")
  [[ -n "$auth" ]] && args+=(-H "Authorization: Bearer $auth")

  local status
  status=$(curl "${args[@]}" "$url")

  if [[ "$status" == "$expect" ]]; then
    echo "  ✓  $desc ($status)"
    ((PASS++))
  else
    echo "  ✗  $desc (got $status, expected $expect)"
    ((FAIL++))
    ERRORS+=("$desc: got $status")
  fi
}

echo ""
echo "=== CPIntel E2E Test Suite ==="
echo ""

# ── Auth ──────────────────────────────────────────────────────
echo "Auth endpoints:"

# Register new user for this test run
TS=$(date +%s)
REG=$(curl -s -X POST "$BASE/api/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"testuser$TS\",\"email\":\"test$TS@test.com\",\"password\":\"Test1234!\",\"fullName\":\"Test\"}")

STATUS=$(echo "$REG" | python3 -c "import sys,json; d=json.load(sys.stdin); print('ok' if d.get('success') else 'fail')" 2>/dev/null)
if [[ "$STATUS" == "ok" ]]; then
  echo "  ✓  POST /api/auth/register (201)"
  ((PASS++))
else
  echo "  ✗  POST /api/auth/register"
  ((FAIL++))
  ERRORS+=("Register failed: $REG")
fi

TOKEN=$(echo "$REG" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null)
REFRESH=$(echo "$REG" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['refreshToken'])" 2>/dev/null)

check "POST /api/auth/login" POST "$BASE/api/auth/login" \
  "{\"email\":\"test$TS@test.com\",\"password\":\"Test1234!\"}" "" 200

check "POST /api/auth/refresh" POST "$BASE/api/auth/refresh" \
  "{\"refreshToken\":\"$REFRESH\"}" "" 200

check "POST /api/auth/login (bad pass)" POST "$BASE/api/auth/login" \
  "{\"email\":\"test$TS@test.com\",\"password\":\"wrong\"}" "" 401

check "POST /api/auth/register (dupe email)" POST "$BASE/api/auth/register" \
  "{\"username\":\"other$TS\",\"email\":\"test$TS@test.com\",\"password\":\"Test1234!\"}" "" 409

# ── Users ─────────────────────────────────────────────────────
echo ""
echo "User endpoints:"
check "GET  /api/users/me"        GET  "$BASE/api/users/me"        "" "$TOKEN" 200
check "PUT  /api/users/me"        PUT  "$BASE/api/users/me"        '{"fullName":"Updated Name"}' "$TOKEN" 200
check "GET  /api/users/dashboard" GET  "$BASE/api/users/dashboard" "" "$TOKEN" 200
check "GET  /api/users/me (no auth)" GET "$BASE/api/users/me"      "" ""       401

# ── Integrations ──────────────────────────────────────────────
echo ""
echo "Integration endpoints:"
check "GET  /api/integrations"    GET  "$BASE/api/integrations" "" "$TOKEN" 200

# ── Analytics ─────────────────────────────────────────────────
echo ""
echo "Analytics endpoints:"
check "GET  /api/analytics/overview"  GET "$BASE/api/analytics/overview"  "" "$TOKEN" 200
check "GET  /api/analytics/topics"    GET "$BASE/api/analytics/topics"    "" "$TOKEN" 200
check "GET  /api/analytics/contests"  GET "$BASE/api/analytics/contests"  "" "$TOKEN" 200
check "GET  /api/analytics/trends"    GET "$BASE/api/analytics/trends"    "" "$TOKEN" 200
check "POST /api/analytics/refresh"   POST "$BASE/api/analytics/refresh"  "" "$TOKEN" 200

# ── Recommendations ───────────────────────────────────────────
echo ""
echo "Recommendation endpoints:"
check "GET  /api/recommendations/daily"    GET "$BASE/api/recommendations/daily"    "" "$TOKEN" 200
check "GET  /api/recommendations/weekly"   GET "$BASE/api/recommendations/weekly"   "" "$TOKEN" 200
check "GET  /api/recommendations/revision" GET "$BASE/api/recommendations/revision" "" "$TOKEN" 200

# ── Roadmap ───────────────────────────────────────────────────
echo ""
echo "Roadmap endpoints:"
check "GET  /api/roadmaps/current"    GET  "$BASE/api/roadmaps/current"   "" "$TOKEN" 200
check "POST /api/roadmaps/regenerate" POST "$BASE/api/roadmaps/regenerate" "" "$TOKEN" 200

# ── Misc ──────────────────────────────────────────────────────
echo ""
echo "Health endpoints:"
check "GET  /actuator/health" GET "$BASE/actuator/health" "" "" 200
check "GET  /api/ping"        GET "$BASE/api/ping"        "" "" 200

# ── Swagger ───────────────────────────────────────────────────
echo ""
echo "API docs:"
check "GET  /v3/api-docs"     GET "$BASE/v3/api-docs"     "" "" 200
check "GET  /swagger-ui.html" GET "$BASE/swagger-ui.html" "" "" 200

# ── Summary ───────────────────────────────────────────────────
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Passed: $PASS"
echo "  Failed: $FAIL"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if [[ ${#ERRORS[@]} -gt 0 ]]; then
  echo ""
  echo "Failures:"
  for e in "${ERRORS[@]}"; do
    echo "  - $e"
  done
fi

[[ $FAIL -eq 0 ]] && echo "" && echo "All tests passed ✓" && exit 0
exit 1
