# Deployment requirements

What a production CPIntel deployment needs from the college's IT team or whoever runs the server,
and what has already been built in this repository. DOMjudge is already deployed and is out of
scope, apart from CPIntel being able to reach it.

Expected scale: about 2,000 accounts, with sittings of about 200 candidates at once.

Each item is marked **Required** (nothing works without it), **Depends** (needed in some setups)
or **Optional** (recommended, not blocking). Tick items off as they are arranged.

## Target layout

```
                     Internet / campus LAN
                              │ 443 (HTTPS)
┌─────────────────────────────┼──────── CPIntel server (one VM, Docker Compose) ─┐
│                      ┌──────▼──────┐                                           │
│                      │    nginx    │  HTTPS, rate limits, security headers     │
│                      └──┬───────┬──┘                                           │
│               /api/*    │       │   /*                                         │
│             ┌───────────▼─┐   ┌─▼──────────┐                                   │
│             │   backend   │   │  frontend  │  (React website)                  │
│             │ Spring Boot │   └────────────┘                                   │
│             └┬───┬───┬───┬┘                                                    │
│   ┌──────────▼┐ ┌▼────┐ ┌▼────┐ ┌▼────────────────┐                            │
│   │ Postgres  │ │Mongo│ │Redis│ │ runner (sandbox, │   optional "monitoring":  │
│   └───────────┘ └─────┘ └─────┘ │ no network)      │   Prometheus·Grafana·Loki │
│                                 └──────────────────┘                           │
└────────────────────────────────────────────────────────────────────────────────┘
        │ HTTPS                                                  ▲
┌───────▼──────────┐   ┌──────────────────┐        ┌─────────────┴──────────────┐
│ DOMjudge         │   │ Codeforces API   │        │ Clients                    │
│ (existing)       │   │ (ratings)        │        │ • web browser + extension  │
└──────────────────┘   └──────────────────┘        │ • desktop app (exams)      │
                                                   └────────────────────────────┘
```

---

## 1. What the admin needs to provide

### 1.1 Server

One Linux virtual machine runs the whole of CPIntel in Docker: the website, the API, the
databases, and the sandbox where students' code is compiled and run.

- [ ] **A VM with 4 vCPU and 8 GB RAM** — *Required.* Comfortable for about 200 candidates at
  once. Ask for 8 vCPU if a full room will press Run often, since each running program uses
  about one core.
- [ ] **40 GB+ SSD, plus room for local backups** — *Required.*
- [ ] **Ubuntu 22.04 or 24.04 LTS with Docker Engine and the Docker Compose plugin** —
  *Required.* Nothing else needs to be installed on the host.
- [ ] **On Ubuntu 24.04: allow unprivileged user namespaces** — *Depends.* The code sandbox needs
  them, and 24.04 blocks them by default. Without this, the Run button stays switched off (it
  never runs code unsandboxed).

  ```sh
  echo 'kernel.apparmor_restrict_unprivileged_userns=0' | sudo tee /etc/sysctl.d/60-cpintel.conf
  sudo sysctl --system
  ```
- [ ] **SSH access with key authentication for whoever deploys** — *Required.*

### 1.2 Network

Only HTTPS needs to be reachable from outside. The server also has to reach a few hosts on its own.

- [ ] **Inbound ports 443 and 80 open; everything else closed** — *Required.* Port 80 only
  redirects to HTTPS and renews the certificate.
- [ ] **Outbound access from the server** — *Required.*
  - the DOMjudge server
  - `ghcr.io`, to download CPIntel's images
  - `codeforces.com`, for rating sync and practice
  - the mail server, if email is used
- [ ] **Lab PCs can reach the server on port 443** — *Required.*
- [ ] **Whether the lab PCs share one IP address through NAT** — *Required.* If they do, we need
  the lab's address range (for example `10.20.0.0/16`) for `CPINTEL_LAB_NETWORKS`. Otherwise 200
  people signing in at once look like one attacker to the rate limiter and get blocked.

### 1.3 Domain and certificate

Sign-ins and exam passwords travel over this connection, so it must be HTTPS with a certificate
the lab browsers trust.

- [ ] **A hostname pointing at the server** — *Required.* For example `cpintel.<college>.edu`,
  with a DNS record for the server's IP.
- [ ] **A certificate** — *Required.* One of:
  - **Server reachable from the internet:** just an email address for Let's Encrypt
    (`CPINTEL_ACME_EMAIL`). The certificate is then issued and renewed automatically
    (`docker compose --profile letsencrypt up -d`).
  - **Campus-only server:** a certificate and private key from campus IT, placed in
    `nginx/certs/`. Every lab PC must trust the authority that issued it.

  Until either exists, nginx serves a temporary self-signed certificate.

### 1.4 DOMjudge

Contests and examinations are judged on the existing DOMjudge. CPIntel submits to it on each
student's behalf.

- [ ] **DOMjudge's URL as seen from the CPIntel server** — *Required.*
- [ ] **One DOMjudge account per candidate** — *Required.* Username and password for each. A
  CPIntel admin attaches them to the students' CPIntel accounts.
- [ ] **The DOMjudge contests themselves** — *Required.* Contest IDs, with start and end times
  that match what is set in CPIntel.
- [ ] **A DOMjudge service account** — *Optional.* Recommended for large sittings: one request
  then serves the whole room instead of one per student. Check its permissions first with
  `scripts/domjudge-probe.sh`.

### 1.5 Secrets and settings

These go in one file, `.env`, on the server (start from `.env.example`). It should be readable
only by the deploy user (`chmod 600`) and never committed anywhere.

Random keys are made with `openssl rand -base64 48`. Keep a copy of the whole file somewhere safe
off the server: losing the keys makes stored exam passwords and DOMjudge logins unreadable.

| Setting | Needed | What it is |
| --- | --- | --- |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | Required | Database login |
| `MONGO_USER` / `MONGO_PASSWORD` | Required | Database login |
| `REDIS_PASSWORD` | Required | Cache password |
| `JWT_SECRET` | Required | Random, at least 32 characters; signs sign-in sessions |
| `CPINTEL_SESSION_KEY` | Required | Random key |
| `CPINTEL_EXAM_PASSWORD_KEY` | Required | Random key. Without it no exam can be sat |
| `CPINTEL_DOMJUDGE_CREDENTIAL_KEY` | Required | Random key; encrypts the students' DOMjudge logins |
| `CPINTEL_RUNNER_TOKEN` | Required | Random key; lets the API talk to the code sandbox |
| `CPINTEL_DOMAIN` | Required | The hostname only, e.g. `cpintel.college.edu` |
| `CPINTEL_PUBLIC_URL` | Required | `https://` plus the hostname |
| `CPINTEL_DOMJUDGE_URL` | Required | From 1.4 |
| `CPINTEL_ADMIN_EMAIL` / `CPINTEL_ADMIN_PASSWORD` | First boot | Creates the first super admin. Remove the password afterwards |
| `CPINTEL_DOMJUDGE_USER` / `CPINTEL_DOMJUDGE_PASSWORD` | Depends | Only with the service account from 1.4 |
| `CPINTEL_ACME_EMAIL` | Depends | Only with Let's Encrypt; receives certificate expiry notices |
| `CPINTEL_LAB_NETWORKS` | Depends | Only if the lab is behind NAT; the address range from 1.2 |
| `CPINTEL_BACKUP_REMOTE` | Optional | Where nightly backups are copied (1.7) |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `CPINTEL_MAIL_FROM` | Optional | Mail server for password-reset emails. Without it, reset links are written to the server log for an admin to pass on. Exam passwords are never emailed either way |
| `GRAFANA_PASSWORD` | Optional | Only with the `monitoring` profile |

- [ ] **`.env` written on the server** — *Required.*
- [ ] **A copy of the keys stored safely off the server** — *Required.*

### 1.6 GitHub

GitHub builds CPIntel. The server downloads the finished images from GitHub's container registry
(GHCR).

- [ ] **A read-only token so the server can pull images from GHCR** — *Required.*
- [ ] **Repository variable `CPINTEL_SERVER_URL`** — *Required.* The `https://` address. It is
  built into the desktop app so the app only ever talks to this server.
- [ ] **Repository variable `CPINTEL_EXTENSION_URL`** — *Optional.* The browser extension's store
  link (1.8), so the website can point people to it.
- [ ] **An `NVD_API_KEY` repository secret** — *Optional.* Free from
  <https://nvd.nist.gov/developers/request-an-api-key>; speeds up the security scan in CI.

### 1.7 Backups

Both databases are dumped every night and kept for 14 days (`scripts/backup.sh`, from cron). A
copy has to live off the server, or a disk failure loses everything.

- [ ] **An off-server place for the nightly dumps** — *Required.* Another machine, object
  storage, or a mounted network share.
- [ ] **One test restore after the first nightly backup** — *Required.* Run
  `scripts/restore.sh --test <stamp>` once and write down the result. (Verified on a development
  copy, 2026-09-24.)

### 1.8 Lab PCs and browsers

Exams are best sat in the CPIntel desktop app, which applies the exam's lockdown settings and
records time spent away. Practice works in any browser.

- [ ] **A place to distribute the desktop app installer** — *Required.* Windows, Linux and macOS
  builds are produced by GitHub on each release (a `v*` tag).
- [ ] **Permission to install it on every lab PC** — *Required.*
- [ ] **The CPIntel browser extension** — *Optional.* Only needed for Codeforces practice in a
  web browser. One of:
  - a Chrome Web Store developer account (one-off fee) to publish it, or
  - permission to install it unpacked on the lab PCs.

### 1.9 People and exam-day process

Decisions and routines the college owns rather than the software.

- [ ] **Who the first super admin is** — *Required.*
- [ ] **Class rosters** — *Required.* Username or email, full name, Codeforces handle and team for
  each student, as a CSV file or copied straight out of a spreadsheet. Accounts are created from
  it in one import.
- [ ] **An exam-day routine** — *Required.*
  - Publish the paper and assign the candidates.
  - Click **Issue passwords**, download the spreadsheet and print one slip per candidate. These
    exam sign-in passwords are new for every exam and work from one hour before the start until
    the end.
  - Optionally, generate a room password to read out at the start.
- [ ] **No updates while an exam is running** — *Required.* A restart drops every live session
  for about a minute.
- [ ] **Tell users they will need to sign in again after the first deploy** — *Optional.*

---

## 2. Already built in this repository

Everything the deployment needs on the code side is done and was run end to end as a full Docker
stack (nginx with HTTPS, backend, sandboxed runner, website) on 2026-09-24:
`scripts/e2e-test.sh` passed 18 of 18 checks, and a headless browser signed in, used the editor
and survived a page reload with no Content-Security-Policy violations.

- [x] **Desktop app talks to the central server.** The release build loads `https://<server>`
  (fixed at build time from `CPINTEL_SERVER_URL`) and no longer tries to start a local backend.
- [x] **HTTPS.** 443 with HSTS and a Content-Security-Policy; 80 only redirects. Let's Encrypt
  with auto-renewal, a campus certificate in `nginx/certs/`, or a temporary self-signed one,
  picked up within a minute without a restart.
- [x] **Production compose file.** `docker-compose.prod.yml` pulls
  `ghcr.io/ayushtiwari1405/cpintel-{backend,runner,frontend,nginx}`, tagged by commit; nothing but
  nginx is published on public interfaces.
- [x] **Deploy script.** `./deploy.sh <version>` backs up both databases, pulls, restarts, waits
  for the health check and rolls back on failure; `./deploy.sh --rollback` returns to the
  previous version.
- [ ] Optional: enable the commented-out SSH deploy job in `ci.yml` for automatic deploys.
- [x] **Backups.** Nightly `pg_dump` + `mongodump`, 14 days kept, copied to
  `CPINTEL_BACKUP_REMOTE`; `scripts/restore.sh` restores, and `--test` restores into a scratch
  copy.
- [x] **Lab behind NAT.** `CPINTEL_LAB_NETWORKS` sizes both nginx's and the backend's
  per-address limits for a room; per-account limits are unchanged.
- [x] **Run on a server.** A separate runner container with no network, a read-only filesystem,
  no capabilities and a seccomp profile; every run in its own bubblewrap sandbox. It refuses to
  run anything if the sandbox cannot be built.
- [x] **Codeforces from a hosted server.** Statements and submissions go through the student's
  browser (the extension, or the desktop app's own session), with statements cached for everyone.
- [x] **Backend image on glibc.** Password hashing loads a native library that crashed the JVM on
  the old Alpine image at the first sign-in; the backend now runs on Ubuntu like the runner.

---


