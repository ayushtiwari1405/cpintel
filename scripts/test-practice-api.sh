#!/usr/bin/env bash
#
# End-to-end smoke test for the practice endpoints against a running backend.
#
#   ./scripts/test-practice-api.sh
#
# Env:
#   API        base URL                       (default http://localhost:8080)
#   EMAIL      CPIntel login                  (default dev@cpintel.local)
#   PASSWORD   CPIntel password
#   CF_COOKIE  Cookie header from a logged-in codeforces.com tab
#              — without it, session/submit steps are skipped
#   ALLOW_SUBMIT=true  actually submit to Codeforces (off by default)
#
set -uo pipefail

API="${API:-http://localhost:8080}"
EMAIL="${EMAIL:-dev@cpintel.local}"
PASSWORD="${PASSWORD:-}"
CONTEST="${CONTEST:-4}"
INDEX="${INDEX:-A}"

pass() { printf '  \033[32mok\033[0m   %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAILED=1; }
skip() { printf '  \033[33mskip\033[0m %s\n' "$1"; }
step() { printf '\n\033[1m%s\033[0m\n' "$1"; }
FAILED=0

command -v jq >/dev/null || { echo "jq is required (apt install jq)"; exit 1; }

# ---------------------------------------------------------------- auth

step "1. Logging in to CPIntel"
if [ -z "$PASSWORD" ]; then
  echo "  Set PASSWORD (and EMAIL) to a CPIntel account." >&2
  exit 1
fi

TOKEN=$(curl -sS -X POST "$API/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" \
  | jq -r '.data.accessToken // empty')

[ -n "$TOKEN" ] || { fail "no access token — is the backend up and the account valid?"; exit 1; }
pass "got an access token"
AUTH=(-H "Authorization: Bearer $TOKEN")

# ---------------------------------------------------------- problem fetch

step "2. Problem search"
COUNT=$(curl -sS "${AUTH[@]}" "$API/api/practice/problems?minRating=800&maxRating=1000&limit=5" \
  | jq '.data | length')
[ "${COUNT:-0}" -gt 0 ] && pass "$COUNT problems returned" || fail "search returned nothing"

step "3. Statement fetch ($CONTEST$INDEX)"
DETAIL=$(curl -sS "${AUTH[@]}" "$API/api/practice/problems/$CONTEST/$INDEX")
echo "$DETAIL" | jq -r '
  "  name    : \(.data.name)",
  "  limits  : \(.data.timeLimit) / \(.data.memoryLimit)",
  "  samples : \(.data.samples | length)",
  "  legend  : \(.data.legendHtml | if . then "\(. | length) chars" else "MISSING" end)"'
[ "$(echo "$DETAIL" | jq -r '.data.statementAvailable')" = "true" ] \
  && pass "statement scraped" || fail "statement not available — check the scraper selectors"
[ "$(echo "$DETAIL" | jq '.data.samples | length')" -gt 0 ] \
  && pass "samples parsed" || fail "no samples — div.sample-tests markup may have changed"

# ------------------------------------------------------------- session

step "4. Codeforces session"
if [ -z "${CF_COOKIE:-}" ]; then
  skip "CF_COOKIE not set — skipping session, languages and submit"
  echo
  exit $FAILED
fi

RESP=$(jq -n --arg c "$CF_COOKIE" '{cookieHeader:$c}' \
  | curl -sS -X POST "${AUTH[@]}" "$API/api/practice/cf-session" \
      -H 'Content-Type: application/json' -d @-)

HANDLE=$(echo "$RESP" | jq -r '.data.handle // empty')
if [ -n "$HANDLE" ]; then
  pass "connected as $HANDLE"
else
  fail "session rejected: $(echo "$RESP" | jq -r '.message // .')"
  exit 1
fi

step "5. Language dropdown"
LANGS=$(curl -sS "${AUTH[@]}" "$API/api/practice/languages")
echo "$LANGS" | jq -r '.data[] | "  \(.id)\t\(.label)"' | head -20
[ "$(echo "$LANGS" | jq '.data | length')" -gt 0 ] \
  && pass "scraped from the live submit page" || fail "no languages returned"

# -------------------------------------------------------------- submit

step "6. Submit"
if [ "${ALLOW_SUBMIT:-}" != "true" ]; then
  skip "set ALLOW_SUBMIT=true to make a real submission to $HANDLE"
  echo
  exit $FAILED
fi

LANG_ID="${LANG_ID:-$(echo "$LANGS" | jq -r '.data[] | select(.label | test("G\\+\\+")) | .id' | head -1)}"
SOURCE='#include <bits/stdc++.h>
int main() { int w; std::cin >> w; std::cout << (w > 2 && w % 2 == 0 ? "YES" : "NO"); }'

RESP=$(jq -n --arg s "$SOURCE" --arg l "$LANG_ID" \
  '{contestId:4, index:"A", languageId:$l, source:$s}' \
  | curl -sS -X POST "${AUTH[@]}" "$API/api/practice/submit" \
      -H 'Content-Type: application/json' -d @-)

SUB_ID=$(echo "$RESP" | jq -r '.data.submissionId // empty')
if [ -z "$SUB_ID" ]; then
  fail "submit failed: $(echo "$RESP" | jq -r '.message // .')"
  exit 1
fi
pass "submission $SUB_ID created"

step "7. Verdict polling"
for i in $(seq 1 20); do
  V=$(curl -sS "${AUTH[@]}" "$API/api/practice/submissions/$SUB_ID/verdict")
  VERDICT=$(echo "$V" | jq -r '.data.verdict')
  printf '  %-24s\r' "$VERDICT"
  [ "$(echo "$V" | jq -r '.data.finished')" = "true" ] && break
  sleep 3
done
echo
[ "$VERDICT" = "OK" ] && pass "verdict: Accepted" || fail "verdict: $VERDICT"

echo
exit $FAILED
