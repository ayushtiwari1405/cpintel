#!/bin/bash
# Runs the CI pipeline (.github/workflows/ci.yml) locally, so a broken build is caught before
# it is pushed rather than after.
#
# Everything runs against a clean snapshot of the repo in a temp directory, never the working
# tree: CI starts from a fresh checkout, and a local node_modules, target/ or public/mathjax
# is exactly the kind of thing that makes a build pass here and fail there.
#
#   ./scripts/ci-local.sh                 # working tree as it is now, uncommitted changes included
#   ./scripts/ci-local.sh --ref HEAD      # exactly what a commit contains (what the pre-push hook uses)
#   ./scripts/ci-local.sh --no-docker     # skip the image builds (the slow part) and the nginx container test
#   ./scripts/ci-local.sh --no-cache      # build images without the layer cache, as a cold CI runner does
#   ./scripts/ci-local.sh --audit         # also the dependency audits: backend scan (needs trivy), frontend; NVD_API_KEY adds OWASP
#
# Stages mirror the CI jobs: workflow lint, backend tests + coverage gate, frontend type check,
# tests + build, desktop tests, the helper and edge nginx script tests, backend and frontend Docker
# images. Each stage logs to a file; a failure prints the
# end of its log and the run carries on, so one push attempt reports every broken stage.
set -uo pipefail

REPO="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
REF=""
DOCKER=1
AUDIT=0
NO_CACHE=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --ref)       REF="$2"; shift 2 ;;
    --no-docker) DOCKER=0; shift ;;
    --no-cache)  NO_CACHE=(--no-cache); shift ;;
    --audit)     AUDIT=1; shift ;;
    -h|--help)   sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)           echo "unknown option: $1 (see --help)" >&2; exit 2 ;;
  esac
done

WORK="$(mktemp -d -t cpintel-ci.XXXXXX)"
SRC="$WORK/src"
LOGS="$WORK/logs"
mkdir -p "$SRC" "$LOGS"
TAG="cpintel-ci-local-$$"
cleanup() {
  rm -rf "$WORK"
  docker rmi -f "$TAG-backend" "$TAG-frontend" "$TAG-runner" "$TAG-nginx" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# ── Snapshot ──────────────────────────────────────────────────
if [[ -n "$REF" ]]; then
  echo "Snapshot: $REF ($(git -C "$REPO" rev-parse --short "$REF"))"
  git -C "$REPO" archive "$REF" | tar -x -C "$SRC"
else
  echo "Snapshot: working tree (tracked + untracked, minus .gitignore'd files)"
  # --ignore-failed-read: a tracked file deleted but not yet committed is simply absent.
  (cd "$REPO" && git ls-files -z -co --exclude-standard \
    | tar --null -T - --ignore-failed-read -cf - 2>/dev/null) | tar -x -C "$SRC"
fi

PASSED=()
FAILED=()
SKIPPED=()

stage() {
  local name="$1"; shift
  local log="$LOGS/${name// /-}.log"
  local start=$SECONDS
  printf '  %-28s' "$name"
  if "$@" >"$log" 2>&1; then
    echo "✓  ($((SECONDS - start))s)"
    PASSED+=("$name")
  else
    echo "✗  ($((SECONDS - start))s)"
    FAILED+=("$name")
    echo "    ── last 25 lines of $name ──"
    tail -25 "$log" | sed 's/^/    │ /'
  fi
}

skip() {
  printf '  %-28s–  skipped: %s\n' "$1" "$2"
  SKIPPED+=("$1")
}

have_docker() { command -v docker >/dev/null && docker info >/dev/null 2>&1; }

# ── Stages ────────────────────────────────────────────────────
lint_workflows() {
  # Files are named explicitly because the snapshot has no .git for actionlint to find the
  # project by; --user because the mktemp directory is readable only by us.
  (cd "$SRC" && docker run --rm --user "$(id -u):$(id -g)" -v "$SRC:/repo" -w /repo \
    rhysd/actionlint:latest -no-color .github/workflows/*.yml)
}

backend_tests() (
  cd "$SRC/backend" && ./mvnw -B verify -q
)

frontend_build() (
  cd "$SRC/frontend" && npm ci --no-audit --no-fund && npm run type-check && npm run lint && npm test \
    && npm run build
)

desktop_tests() (
  # The tests never start Electron, so its ~100 MB binary is not downloaded (as in CI).
  cd "$SRC/electron" && ELECTRON_SKIP_BINARY_DOWNLOAD=1 npm ci --no-audit --no-fund && npm test
)

script_tests() (
  # The edge test's runtime half starts two throwaway containers; --no-docker leaves only its
  # static checks.
  cd "$SRC" && python3 -I scripts/test_grab_cf_cookie.py \
    && if [[ $DOCKER -eq 0 ]]; then CPINTEL_NO_DOCKER=1 python3 -I scripts/test_nginx_edge.py;
       else python3 -I scripts/test_nginx_edge.py; fi \
    && if [[ $DOCKER -eq 0 ]]; then CPINTEL_NO_DOCKER=1 python3 -I scripts/test_mongo_app_user.py;
       else python3 -I scripts/test_mongo_app_user.py; fi \
    && python3 -I scripts/loadtest/test_stack.py \
    && python3 -I scripts/integration/test_isolation.py \
    && if [[ $DOCKER -eq 0 ]]; then CPINTEL_NO_DOCKER=1 python3 -I scripts/test_mongo_index_upgrade.py \
         && CPINTEL_NO_DOCKER=1 python3 -I scripts/integration/test_cleanup_lock.py;
       else python3 -I scripts/test_mongo_index_upgrade.py && python3 -I scripts/integration/test_cleanup_lock.py; fi \
    && if [[ $DOCKER -eq 0 ]]; then CPINTEL_NO_DOCKER=1 python3 -I scripts/test_compose_secrets.py;
       else python3 -I scripts/test_compose_secrets.py; fi \
    && if [[ $DOCKER -eq 0 ]]; then CPINTEL_NO_DOCKER=1 python3 -I scripts/test_backup_restore.py;
       else python3 -I scripts/test_backup_restore.py; fi
)

docker_backend()  { docker build "${NO_CACHE[@]}" -t "$TAG-backend"  "$SRC/backend"; }
docker_frontend() { docker build "${NO_CACHE[@]}" -t "$TAG-frontend" "$SRC/frontend"; }
docker_runner()   { docker build "${NO_CACHE[@]}" --target runner -t "$TAG-runner" "$SRC/backend"; }
docker_nginx()    { docker build "${NO_CACHE[@]}" -t "$TAG-nginx" "$SRC/nginx"; }

audit_backend() (
  cd "$SRC/backend" && ./mvnw -B org.owasp:dependency-check-maven:13.0.0:check -q \
    -DfailBuildOnCVSS=9 -DnvdApiKey="$NVD_API_KEY"
)

scan_backend() (
  # The blocking scan CI runs: the jar the backend stage built, with Trivy.
  cd "$SRC" && jars=(backend/target/cpintel-backend-*.jar) && scripts/scan-backend-deps.sh "${jars[0]}"
)

audit_frontend() (
  # Reuses the node_modules frontend_build installed, if it ran.
  cd "$SRC/frontend" && npm audit --audit-level=high
)

echo ""
echo "=== CPIntel local CI ==="
echo ""

if have_docker; then
  stage "workflow lint" lint_workflows
else
  skip "workflow lint" "docker not available"
fi

stage "backend tests + coverage" backend_tests
stage "frontend type-check + tests + build" frontend_build
stage "desktop tests" desktop_tests
stage "script tests" script_tests

if [[ $DOCKER -eq 0 ]]; then
  skip "docker: backend image" "--no-docker"
  skip "docker: frontend image" "--no-docker"
elif have_docker; then
  stage "docker: backend image" docker_backend
  stage "docker: frontend image" docker_frontend
  stage "docker: runner image" docker_runner
  stage "docker: nginx image" docker_nginx
else
  skip "docker: backend image" "docker not available"
  skip "docker: frontend image" "docker not available"
fi

if [[ $AUDIT -eq 1 ]]; then
  if command -v trivy >/dev/null; then
    stage "audit: backend dependency scan (trivy)" scan_backend
  else
    skip "audit: backend dependency scan (trivy)" "trivy not installed"
  fi
  if [[ -n "${NVD_API_KEY:-}" ]]; then
    stage "audit: backend (OWASP, second opinion)" audit_backend
  else
    skip "audit: backend (OWASP, second opinion)" "NVD_API_KEY not set"
  fi
  stage "audit: frontend" audit_frontend
fi

echo ""
echo "Passed: ${#PASSED[@]}   Failed: ${#FAILED[@]}   Skipped: ${#SKIPPED[@]}"
if [[ ${#FAILED[@]} -gt 0 ]]; then
  echo "Failed: ${FAILED[*]}"
  exit 1
fi
