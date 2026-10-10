#!/usr/bin/env bash
# Builds the desktop installers for one CPIntel server, for the server itself to hand out.
#
#   scripts/build-desktop.sh https://cpintel.example.edu          # what deploy.sh runs
#   scripts/build-desktop.sh http://100.113.222.20:5173 --http    # a throwaway test server
#   scripts/build-desktop.sh <url> --force                        # rebuild even if unchanged
#
# The app's server address is fixed at build time (electron/scripts/stamp-server.mjs says why),
# so the installers have to be built for the address students will use. Building them where the
# server is deployed means a new address costs a redeploy, not a hand-made build.
#
# Everything runs inside electron-builder's own image, which carries Node and Wine, so the
# server needs nothing but Docker. Out come:
#
#   Windows   CPIntel-Setup-<v>-x64.exe
#   Linux     CPIntel-<v>-x86_64.AppImage, CPIntel-<v>-amd64.deb
#   macOS     CPIntel-<v>-arm64.zip, CPIntel-<v>-x64.zip   (a .dmg can only be made on a Mac)
#
# in desktop/current/ with a release.json listing them, which the backend serves on /download
# and to the installed apps' update ribbon. A new build is made in desktop/next/ and swapped in
# whole, so the download page never sees half of one.
#
# Skipped when desktop/current/ was already built for this address from this app source: an
# ordinary redeploy costs nothing. A full build takes 10-20 minutes and about 3 GB of downloads
# the first time (the image, Electron, and the compilers the app ships with); those are kept in
# desktop/cache/ for the next one.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="electronuserland/builder:20-wine@sha256:aec4b2175b923382e298afa8f3ee680b6aa3890b738ddcf228ddf038bc7c2f5c"

# ── Inside the container ─────────────────────────────────────────────────────────────────────
if [ "${1:-}" = "--inside" ]; then
  cd /desktop/work/electron
  OUT=/desktop/next
  # The compilers are unpacked in the cache and renamed into the app, so the cache has to be on
  # the same mount as the source: a rename cannot cross from one Docker mount to another.
  ln -sfn /desktop/cache/toolchain .toolchain-cache
  npm ci --no-audit --no-fund
  npx tsc -p .
  node scripts/stamp-server.mjs

  # Each platform carries its own compilers, so each is packaged after fetching them.
  # -c.directories.output keeps the repository's own electron/release/ out of it.
  STAGE=/tmp/stage
  build() {   # <toolchain target> <electron-builder arguments...>
    local target="$1"; shift
    echo "=== $target ==="
    CPINTEL_TOOLCHAIN_TARGET="$target" node scripts/fetch-toolchains.mjs
    node scripts/package.mjs "$@" -c.directories.output="$STAGE"
  }
  build linux-x64    --linux AppImage deb --x64
  build win32-x64    --win nsis --x64
  build darwin-arm64 --mac dir --arm64
  build darwin-x64   --mac dir --x64

  version="$(node -p "require('./package.json').version")"
  cp "$STAGE/CPIntel-Setup-$version-x64.exe" "$STAGE/CPIntel-$version-x86_64.AppImage" \
     "$STAGE/CPIntel-$version-amd64.deb" "$OUT/"
  # Zipped here rather than by electron-builder: its zip, made on Linux, flattens the symlinks
  # inside Electron's framework, and the app then will not start on a Mac. The image has no
  # zip command, so Python's zipfile does it, keeping symlinks and permissions in the form
  # macOS's Archive Utility reads back.
  mac_zip() {   # <directory holding CPIntel.app> <zip>
    python3 - "$1" "$2" <<'PY'
import os, stat, sys, zipfile
base, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for top, dirs, files in os.walk(os.path.join(base, 'CPIntel.app')):
        for name in sorted(dirs) + sorted(files):
            path = os.path.join(top, name)
            arc = os.path.relpath(path, base)
            st = os.lstat(path)
            if stat.S_ISLNK(st.st_mode):
                info = zipfile.ZipInfo(arc)
                info.create_system = 3
                info.external_attr = (stat.S_IFLNK | 0o755) << 16
                z.writestr(info, os.readlink(path))
            elif stat.S_ISDIR(st.st_mode):
                info = zipfile.ZipInfo(arc + '/')
                info.create_system = 3
                info.external_attr = (stat.S_IFDIR | 0o755) << 16 | 0x10
                z.writestr(info, b'')
            else:
                z.write(path, arc)
        # os.walk follows no symlinked directories, so a framework's Versions/Current link is
        # written once, as a link, not walked into as a second copy.
PY
  }
  mac_zip "$STAGE/mac-arm64" "$OUT/CPIntel-$version-arm64.zip"
  mac_zip "$STAGE/mac"       "$OUT/CPIntel-$version-x64.zip"
  echo "$version" > "$OUT/.version"
  exit 0
fi

# ── On the host ──────────────────────────────────────────────────────────────────────────────
URL="${1:?usage: scripts/build-desktop.sh <server url> [--http] [--force]}"
shift
ALLOW_HTTP=0 FORCE=0
for arg in "$@"; do
  case "$arg" in
    --http)  ALLOW_HTTP=1 ;;
    --force) FORCE=1 ;;
    *) echo "Unknown option: $arg"; exit 1 ;;
  esac
done
URL="${URL%/}"

DESKTOP="$ROOT/desktop"
CURRENT="$DESKTOP/current" NEXT="$DESKTOP/next" CACHE="$DESKTOP/cache"
mkdir -p "$CACHE/toolchain" "$CACHE/electron" "$CACHE/electron-builder" "$CACHE/npm"

# What the installers are built from: the address and the app's source. Uncommitted changes
# count too, so a local experiment is never mistaken for the committed app.
source_id="$( (cd "$ROOT" && git rev-parse HEAD:electron HEAD:frontend/src/runner/browser/stdcxxShim.ts \
  && git diff HEAD -- electron frontend/src/runner/browser/stdcxxShim.ts | sha256sum) \
  | sha256sum | cut -c1-16)"
if [ "$FORCE" = 0 ] && [ -f "$CURRENT/release.json" ] \
   && grep -qF "\"serverUrl\": \"$URL\"," "$CURRENT/release.json" \
   && grep -qF "\"source\": \"$source_id\"," "$CURRENT/release.json"; then
  echo "Desktop installers for $URL are up to date."
  exit 0
fi

echo "=== Building the desktop installers for $URL ==="
rm -rf "$NEXT"
mkdir -p "$NEXT"

# The source is copied rather than mounted: npm ci and the build write into it, and the
# repository's own electron/node_modules (or its absence, on a server) is not theirs to change.
WORK="$DESKTOP/work"
rm -rf "$WORK"
mkdir -p "$WORK"
# The toolchain script also takes the <bits/stdc++.h> stand-in from the website's runner.
(cd "$ROOT" && git ls-files -z --cached --others --exclude-standard electron \
    frontend/src/runner/browser/stdcxxShim.ts \
  | xargs -0 cp --parents -t "$WORK")

# As this user, so nothing it writes is owned by root.
docker run --rm \
  --user "$(id -u):$(id -g)" \
  -e HOME=/tmp/home \
  -e CPINTEL_SERVER_URL="$URL" \
  -e CPINTEL_ALLOW_HTTP="$ALLOW_HTTP" \
  -e ELECTRON_CACHE=/desktop/cache/electron \
  -e ELECTRON_BUILDER_CACHE=/desktop/cache/electron-builder \
  -e npm_config_cache=/desktop/cache/npm \
  -v "$DESKTOP:/desktop" \
  -v "$ROOT/scripts/build-desktop.sh:/build-desktop.sh:ro" \
  "$IMAGE" bash /build-desktop.sh --inside

rm -rf "$WORK"

# The list the backend reads. SHA-256s are what the download page shows and what the app's
# updater checks a download against.
version="$(cat "$NEXT/.version")"
rm "$NEXT/.version"
{
  echo '{'
  echo "  \"version\": \"$version\","
  echo "  \"builtAt\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\","
  echo "  \"serverUrl\": \"$URL\","
  echo "  \"source\": \"$source_id\","
  echo '  "assets": ['
  first=1
  for f in "$NEXT"/CPIntel-*; do
    [ "$first" = 1 ] || echo ','
    first=0
    printf '    { "name": "%s", "size": %s, "sha256": "%s" }' \
      "$(basename "$f")" "$(stat -c %s "$f")" "$(sha256sum "$f" | cut -d' ' -f1)"
  done
  echo
  echo '  ]'
  echo '}'
} > "$NEXT/release.json"

# Swap in whole. The backend mounts desktop/, not desktop/current, so it sees the new directory.
rm -rf "$DESKTOP/previous"
[ -d "$CURRENT" ] && mv "$CURRENT" "$DESKTOP/previous"
mv "$NEXT" "$CURRENT"
rm -rf "$DESKTOP/previous"

echo "=== Desktop installers for $URL are in desktop/current/ ==="
ls -lh "$CURRENT"
