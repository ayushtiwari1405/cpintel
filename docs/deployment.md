# Deploying to a server

Images are built by CI (tagged with the commit) and pulled on the server; nothing is compiled
there except the desktop installers. This page goes from an empty server to the first sign-in.
What the college has to provide first (server, DNS, certificate, DOMjudge, accounts) is the
checklist in [requirements.md](requirements.md); every setting is in
[configuration.md](configuration.md).

## 1. Prepare the server

Ubuntu 22.04 or 24.04 with Docker Engine and the Docker Compose plugin, ports 80 and 443 open.

**Ubuntu 24.04 only:** the code sandbox needs unprivileged user namespaces, which 24.04 restricts
by default. Without this every Run is refused:

```bash
echo 'kernel.apparmor_restrict_unprivileged_userns=0' | sudo tee /etc/sysctl.d/60-cpintel.conf
sudo sysctl --system
```

## 2. Get the code onto the server

The deployment repository is private, so the server needs read access to it. Use a **deploy
key** — read-only, and limited to this one repository:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/cpintel_deploy -N '' -C cpintel-server
cat ~/.ssh/cpintel_deploy.pub
```

Add that public key in the repository under **Settings → Deploy keys → Add deploy key** (leave
*Allow write access* off; it takes a repository admin). Then:

```bash
cat >> ~/.ssh/config <<'EOF'
Host github-cpintel
  HostName github.com
  IdentityFile ~/.ssh/cpintel_deploy
  IdentitiesOnly yes
EOF
git clone git@github-cpintel:<owner>/<repo>.git cpintel
cd cpintel
```

The server only needs the scripts and Compose files from the clone; the application itself
comes as images. For later releases, `git pull` before `./deploy.sh`.

## 3. Let the server pull the images

CI pushes four images to GHCR (`ghcr.io/<owner>/<repo>-{backend,runner,frontend,nginx}`, owner
and repository lowercased). A private repository's packages are private too, so the server logs
in once with a token that can only read packages:

1. On GitHub, a member of the organisation creates a **classic personal access token** with only
   the `read:packages` scope (fine-grained tokens do not cover GHCR). Give it an expiry and
   note when it runs out: after that, deploys fail at the pull step.
2. On the server, as the user who runs `deploy.sh`:

   ```bash
   docker login ghcr.io -u <github-username>     # paste the token as the password
   ```

   Docker keeps it in `~/.docker/config.json`. Check it with
   `docker pull ghcr.io/<owner>/<repo>-backend:<commit-sha>`.

If the organisation makes the packages public instead, skip this step.

`CPINTEL_IMAGE_REPO` in `.env` must name the same `<owner>/<repo>`, lowercase.

## 4. Write `.env`

```bash
cp .env.example .env && chmod 600 .env
```

Fill in every secret (random values: `openssl rand -base64 48`), `CPINTEL_DOMAIN`,
`CPINTEL_PUBLIC_URL` (`https://` plus that host), and `CPINTEL_ADMIN_EMAIL` with
`CPINTEL_ADMIN_PASSWORD` for the first super admin. The prod profile refuses to start if
`CPINTEL_EXAM_PASSWORD_KEY` or `CPINTEL_DOMJUDGE_CREDENTIAL_KEY` is missing or shorter than 32
characters.

**Keep a copy of `.env` off the server.** Losing the two keys above makes every stored
examination password and DOMjudge login unreadable; there is no recovery.

- **HTTPS.** A server the internet can reach gets a Let's Encrypt certificate: set
  `CPINTEL_ACME_EMAIL` and run `docker compose --profile letsencrypt up -d` once. On a closed
  campus network, put campus IT's certificate in `nginx/certs/` instead. Either is picked up
  within a minute, without a restart. With neither, nginx serves a temporary self-signed one.
- **A lab behind one NAT address:** set `CPINTEL_LAB_NETWORKS` to its range, or two hundred
  people signing in together hit the per-address limits.
- **Mail:** without `SMTP_HOST`, password-reset emails reach nobody. Set
  `CPINTEL_MAIL_LOG_UNDELIVERED=true` to have reset links written to the backend log for an
  admin to pass on. Examination passwords are never emailed either way.
- **DOMjudge:** each classroom's DOMjudge must be reachable from this server (not just from the
  lab). Check with `curl -sI <domjudge-url>/api/v4/info` from the server.

## 5. Check, then deploy

```bash
python3 -I scripts/deploy-preflight.py --version <commit-sha>
```

Use the full commit SHA of a `main` commit whose CI run went green (that is what tags the
images). The preflight is read-only: every secret present, long enough, not a placeholder, not
shared; the file private; https; mail; backups; the production Compose configuration; the scan
exceptions' deadline. It names settings but never prints their values, exits 1 on a FAIL, and
cannot see the server's data, DNS or network.

```bash
./deploy.sh <commit-sha>         # backup → pull → restart → health check → roll back on failure
./deploy.sh --rollback           # back to the version before
./deploy.sh --force <commit-sha> # even while an examination is running (refused otherwise)
```

While the backend restarts (about half a minute) nginx answers API calls with `503
SERVER_RESTARTING`; the page retries its reads and keeps everyone signed in, but a deploy still
belongs outside examinations, which is why the script refuses one during a paper.

**The first deploy takes 10–20 minutes longer.** After the site is healthy, `deploy.sh` builds
the desktop installers for `CPINTEL_PUBLIC_URL` (Windows, Linux, and macOS as a zip) inside
electron-builder's Docker image, about 3 GB of downloads the first time. The site is already up
while it runs. Later deploys skip it unless the address or the app changed. Set
`CPINTEL_DESKTOP_BUILD=0` to skip it entirely; see [desktop-app.md](desktop-app.md).

After it finishes:

```bash
docker logs cpintel-runner 2>&1 | grep 'sandbox active'   # otherwise see step 1
```

**Upgrading a server whose database predates this release:** its MongoDB indexes, and possibly
duplicate files or contest rules, need
[Upgrading an existing MongoDB](operations.md#upgrading-an-existing-mongodb) *before*
`./deploy.sh`; a fresh install needs nothing.

## 6. First use

In this order:

1. **Sign in as the super admin** named by `CPINTEL_ADMIN_EMAIL`. Then **remove
   `CPINTEL_ADMIN_PASSWORD` from `.env`** (the email can stay); see
   [The first super admin](accounts.md#the-first-super-admin).
2. **Create the classrooms** (one per DOMjudge server) and add their admins and TAs; see
   [classrooms.md](classrooms.md).
3. **Import the rosters.** Generated passwords are shown once and stored hashed — **download
   them on that screen; the download is the only copy.**
4. **Create the examinations**, issue their passwords and publish them; see
   [examinations.md](examinations.md). Rehearse one before the first real paper.
5. **Schedule backups:** `scripts/backup.sh` nightly from cron, with `CPINTEL_BACKUP_REMOTE` for
   the copy off the server, and do one test restore; see
   [Backups and restore](operations.md#backups-and-restore).

## Other notes

- **MongoDB accounts:** the backend signs in as `MONGO_APP_USER` (default `cpintel_app`), which
  holds `readWrite` on the `cpintel` database and nothing else; the root account (`MONGO_USER`) is
  for backups, restores and creating that account. See
  [MongoDB accounts](operations.md#mongodb-accounts).
- **Health:** `/actuator/health`, asked by Docker and `deploy.sh`; see
  [Health checks](operations.md#health-checks).
- **Browsers:** the site's stylesheet (Tailwind CSS 4) needs Chrome or Edge 111+, Firefox 128+
  or Safari 16.4+; anything older loads with a broken layout. On a lab machine, open
  `chrome://version` (or Help → About) and compare. The desktop app carries its own Chromium,
  so machines that use it are unaffected.
- **Codeforces on the website:** a hosted server cannot fetch Codeforces' pages (its browser
  check refuses servers), so website users need the CPIntel extension, which fetches them from
  their own browser. Nothing has to be set up for that: somebody who tries to connect Codeforces
  without it is sent to the site's **/extension** page, which builds the download for that site
  in their browser and walks them through installing it. Optionally, publish it to the Chrome
  Web Store for a one-click install (`CPINTEL_SERVER_URL=https://<server> node
  extension/build.mjs` makes `extension/cpintel-codeforces.zip`) and set the repository
  variable `CPINTEL_EXTENSION_URL` to the listing; the page then offers the store first. See
  `extension/README.md`. The desktop app needs no extension.
- **Cross-origin access: none to configure.** The page and the API come from the same nginx, so
  `CPINTEL_CORS_ALLOWED_ORIGINS` stays empty whatever address the server ends up on — a LAN
  host or IP, an internet name, port 443 or another. nginx passes the host and port the browser
  used (`X-Forwarded-Host`), which is how the backend recognises its own page on a non-standard
  port. If another proxy, firewall or port-forward sits in front of this nginx, it must keep the
  `Host` header the browser sent. Once the address is final, check it from a machine on that
  network (`<origin>` is exactly what people type, e.g. `https://host:port`):

  ```bash
  # same origin: expect 401 (no session), not 403
  curl -sk -o /dev/null -w '%{http_code}\n' -X POST -H "Origin: <origin>" <origin>/api/v1/auth/refresh
  # any other origin: expect 403 and no Access-Control-Allow-Origin header
  curl -sk -D - -o /dev/null -X POST -H 'Origin: https://not-cpintel.example' <origin>/api/v1/auth/refresh
  ```
