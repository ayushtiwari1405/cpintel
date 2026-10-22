#!/usr/bin/env bash
# Scans the libraries inside the built backend jar for known vulnerabilities, with Trivy.
#
#   scripts/scan-backend-deps.sh backend/target/cpintel-backend-*.jar [report.json]
#
# The jar rather than pom.xml: it holds exactly the versions that ship (BOOT-INF/lib), after
# Maven has resolved every transitive dependency and every override.
#
# Policy
#   - Fails on HIGH or CRITICAL findings that have a fixed version to move to. Findings with no
#     fix yet are reported, not failed on: nothing can be done about them in this repository.
#   - Every finding, at every severity, is printed, and written as JSON when a path is given.
#   - Vetted exceptions go in backend/.trivyignore.yaml, each with a statement saying why and an
#     expired_at date, after which it fails again.
#
# Proving it looked
#   Before the real scan it scans .github/security/canary (log4j-core 2.14.1) and requires
#   CVE-2021-44228. A vulnerability database that failed to load, or a scanner that can no longer
#   read Maven artifacts, fails here instead of passing the real scan having checked nothing.
#
# Databases
#   Downloaded into TRIVY_CACHE_DIR (default ~/.cache/trivy) from ghcr.io, falling back to
#   public.ecr.aws; no key or account is needed. If neither answers, a cached copy at most
#   7 days old is used with a warning; with none, the scan fails.
#
# Needs trivy on PATH (CI installs a pinned, checksum-verified release).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="${1:?usage: scripts/scan-backend-deps.sh <backend jar> [report.json]}"
REPORT="${2:-}"
[ -f "$JAR" ] || { echo "!!! No such jar: $JAR"; exit 2; }
command -v trivy >/dev/null || { echo "!!! trivy is not installed."; exit 2; }

CACHE="${TRIVY_CACHE_DIR:-$HOME/.cache/trivy}"
DB_REPOS="ghcr.io/aquasecurity/trivy-db:2,public.ecr.aws/aquasecurity/trivy-db:2"
JAVA_DB_REPOS="ghcr.io/aquasecurity/trivy-java-db:1,public.ecr.aws/aquasecurity/trivy-java-db:1"
MAX_STALE_DAYS=7
IGNORE="$ROOT/backend/.trivyignore.yaml"
CANARY="$ROOT/.github/security/canary"

echo "trivy $(trivy --version | head -1 | awk '{print $2}'), cache $CACHE"

# The age of a cached database, in days, from its metadata; empty if there is none.
db_age_days() {
  local meta="$CACHE/$1/metadata.json"
  [ -f "$meta" ] || return 0
  python3 -I -c '
import json, sys, datetime
d = json.load(open(sys.argv[1]))
t = datetime.datetime.fromisoformat(d["UpdatedAt"].replace("Z", "+00:00"))
print((datetime.datetime.now(datetime.timezone.utc) - t).days)' "$meta"
}

fresh() {   # <cache subdir> <download flag> <repository flag> <repositories>
  local dir="$1" flag="$2" repo_flag="$3" repos="$4" age
  if trivy image --cache-dir "$CACHE" "$flag" "$repo_flag" "$repos" --timeout 30m --quiet; then
    echo "  $dir: up to date"
    return 0
  fi
  age="$(db_age_days "$dir")"
  if [ -n "$age" ] && [ "$age" -le "$MAX_STALE_DAYS" ]; then
    echo "::warning::Could not refresh the $dir vulnerability database; using the cached copy from $age day(s) ago."
    return 0
  fi
  echo "!!! The $dir vulnerability database could not be downloaded and no usable cached copy exists."
  return 1
}

echo "Vulnerability databases"
fresh db --download-db-only --db-repository "$DB_REPOS"
fresh java-db --download-java-db-only --java-db-repository "$JAVA_DB_REPOS"

SCAN=(trivy --cache-dir "$CACHE" --quiet)
OFFLINE=(--skip-db-update --skip-java-db-update --scanners vuln)

echo "Canary: the scanner must find CVE-2021-44228 in log4j-core 2.14.1"
canary="$(mktemp)"
trap 'rm -f "$canary"' EXIT
"${SCAN[@]}" fs "${OFFLINE[@]}" --offline-scan --format json --output "$canary" "$CANARY"
grep -q '"CVE-2021-44228"' "$canary" \
  || { echo "!!! The canary was not flagged: the scan cannot be trusted, so it fails."; exit 1; }
echo "  found"

IGNORE_ARGS=()
[ -f "$IGNORE" ] && IGNORE_ARGS=(--ignorefile "$IGNORE")
SCAN_DIR="$(mktemp -d)"
trap 'rm -f "$canary"; rm -rf "$SCAN_DIR"' EXIT
cp "$JAR" "$SCAN_DIR/"

echo "All findings in $(basename "$JAR"), every severity"
"${SCAN[@]}" rootfs "${OFFLINE[@]}" "${IGNORE_ARGS[@]}" --format table "$SCAN_DIR"
if [ -n "$REPORT" ]; then
  "${SCAN[@]}" rootfs "${OFFLINE[@]}" "${IGNORE_ARGS[@]}" --format json --output "$REPORT" "$SCAN_DIR"
fi

echo "Gate: HIGH or CRITICAL with a fixed version"
"${SCAN[@]}" rootfs "${OFFLINE[@]}" "${IGNORE_ARGS[@]}" --severity HIGH,CRITICAL --ignore-unfixed \
  --exit-code 1 --format table "$SCAN_DIR" \
  || { echo "!!! Fixable HIGH or CRITICAL vulnerabilities in the backend's libraries (above)."; exit 1; }
echo "  none"
