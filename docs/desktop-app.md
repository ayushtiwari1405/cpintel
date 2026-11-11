# Desktop app

The desktop app is a window onto a CPIntel server, not a copy of it: the page, the API and the
examination all come from the server, exactly as in a browser, with the examination lockdown
and the Codeforces window layered on top. In dev mode it opens your running Vite dev server
(`CPINTEL_DEV_URL` overrides `http://localhost:5173`). Start the backend and frontend ([development.md](development.md)),
then:

```bash
cd electron
npm install
npm run dev
```

A release build has the server's address stamped in at build time and reads nothing else, so an
installer cannot be repointed at a server a candidate runs themselves; built without one, it
refuses to start:

```bash
cd electron && CPINTEL_SERVER_URL=https://<server> npm run build
```

The build also bundles the compilers the Run button uses outside examinations — GCC on Windows,
Zig's Clang on macOS and Linux, CPython everywhere — fetched by `scripts/fetch-toolchains.mjs`
from pinned, checksummed URLs into `electron/toolchain`, about 400–520 MB unpacked per platform.
In dev, run `npm run toolchain` once to get them; without them the app runs code in its browser
engine instead.

To check which Electron an installer actually carries, without installing or running it:

```bash
7z x -o/tmp/cpi CPIntel-Setup-<v>-x64.exe '$PLUGINSDIR/app-64.7z'
7z x -o/tmp/cpi '/tmp/cpi/$PLUGINSDIR/app-64.7z' CPIntel.exe
strings -n 8 /tmp/cpi/CPIntel.exe | grep -oE 'Electron/[0-9.]+' | sort -u   # expect the lockfile's version
```

The Electron shell wraps the same React SPA used on web — there is no separate frontend
codebase.

## Publishing the desktop app

The app's server address is fixed at build time, so installers are built for the address
students will use. There are two ways; the first needs no setup.

**Built by the server at deploy (default).** After the site is healthy, `deploy.sh` runs
`scripts/build-desktop.sh` for `CPINTEL_PUBLIC_URL`. It needs only Docker: everything runs in
electron-builder's image (Node and Wine). Out come the Windows installer, the Linux AppImage and
.deb, and macOS zips for Apple Silicon and Intel, in `desktop/current/` with a `release.json`
listing them. The backend serves them on `/download` and to the installed apps' update ribbon.

- A full build takes 10–20 minutes and about 3 GB of downloads the first time (kept in
  `desktop/cache/` afterwards). The site is already up on the new version meanwhile.
- It is skipped when `desktop/current/` was already built for this address from this app
  source, so an ordinary redeploy costs nothing. `scripts/build-desktop.sh <url> --force`
  rebuilds anyway.
- A failed build leaves the previous installers in place and does not fail the deploy.
- `CPINTEL_DESKTOP_BUILD=0` in `.env` turns it off.
- A macOS `.dmg` can only be made on a Mac; use the GitHub route below if you need one.

**Built by GitHub on a tag (optional).** Used by `/download` only when the server has no build
of its own. Once, in the GitHub repository and on the server:

1. **Settings → Secrets and variables → Actions → Variables:** add `CPINTEL_SERVER_URL`, the
   https address students open (e.g. `https://cpintel.college.edu`). Every installer is built
   for that one server.
2. **Server `.env`:** set `CPINTEL_DESKTOP_REPOSITORY` to the repository's `owner/name`. If the
   repository is private, also set `CPINTEL_DESKTOP_GITHUB_TOKEN` to a fine-grained token with
   read access to its Contents; downloads are then streamed through the server.

Then, for every release:

```bash
git tag v1.0.0 && git push <remote> v1.0.0
```

`.github/workflows/electron.yml` builds the installers on Windows, macOS (Apple Silicon and
Intel) and Linux, with the compilers bundled, takes the version from the tag, and publishes them
as a GitHub Release (about 25 minutes).

**What students see.** Within ten minutes of a new build the site's **Desktop app** page
(`/download`, also linked from the sign-in page) offers it with step-by-step instructions for
each system, the warnings students will see and what to do about them, the files' SHA-256
fingerprints, and what should make them stop and ask. Installed apps show a ribbon offering the
update; pressing Update downloads the installer, checks it against the release's SHA-256 and
installs it. Nothing updates on its own. To make an old version stop working with the server,
raise `CPINTEL_MIN_DESKTOP_VERSION`: its ribbon can then not be closed.

The installers are not code-signed, so a first install meets Windows SmartScreen ("Unknown
publisher") and macOS Gatekeeper; the download page walks students through both. Updates made
from inside the app do not, since the app downloads them itself. Signing needs paid
certificates (Apple Developer Program; a Windows code-signing certificate) and is not set up.

The desktop build is also where examination monitoring is strongest: the away-time accounting lives
in the Electron main process, where the page cannot reach it. The browser build does the same
job with the Page Visibility API and says plainly that it is the weaker kind.

It is also the easier place to connect Codeforces: the app signs you in through a window it owns
rather than asking you to run a helper, and the sign-in persists, so reconnecting an expired
session is one click. See [Connecting Codeforces](codeforces.md#connecting-codeforces). Connecting is refused
while an examination lockdown is engaged — a full browser window mid-round would walk around
everything the lock is doing, so it is something to do before the round starts.
