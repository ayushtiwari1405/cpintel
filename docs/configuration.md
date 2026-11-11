# Configuration

Everything below has a working default; only the secrets in `.env` genuinely need setting.

| Variable | Default | What it does |
|---|---|---|
| `CPINTEL_REGISTRATION_ENABLED` | `false` | Whether anyone may create their own account. Off: an admin creates them |
| `CPINTEL_ADMIN_EMAIL` | *(unset)* | The super administrator — see [Roles and accounts](accounts.md#roles-and-accounts) |
| `CPINTEL_ADMIN_PASSWORD` | *(unset)* | Only read when that address matches no account, to create one |
| `CPINTEL_ADMIN_USERNAME` | `superadmin` | Username for that created account |
| `CPINTEL_RUNNER_ENABLED` | `true` | Whether the Run button works at all |
| `CPINTEL_RUNNER_URL` | blank (`http://runner:8090` in `prod`) | The runner container. Blank runs code inside the backend — dev and desktop only; `prod` refuses to start that way |
| `CPINTEL_RUNNER_TOKEN` | — | Shared by the backend and the runner container; required whenever a url is set |
| `CPINTEL_RUNNER_CONCURRENCY` | `3` | Runs executing at once in the runner container; the rest queue for up to 20 s |
| `CPINTEL_RUNNER_TIME_LIMIT_MS` | `5000` | Wall clock per test case |
| `CPINTEL_RUNNER_MEMORY_MB` | `512` | Address-space limit per run, for every language |
| `CPINTEL_RUNNER_CPP` | `g++` | C++ compiler, when running inside the backend (dev/desktop) |
| `CPINTEL_RUNNER_PYTHON` | `python3` | Python interpreter, when running inside the backend (dev/desktop) |
| `CPINTEL_SUBMIT_ENABLED` | `true` | Whether submissions may be forwarded to Codeforces |
| `CPINTEL_SESSION_KEY` | *(none — required)* | Encrypts stored Codeforces session cookies. At least 16 chars |
| `CPINTEL_FILES_IN_CONTEST` | `true` | Whether contests offer personal files by default |
| `CPINTEL_FILES_MAX_FILE_BYTES` | `2097152` | Per-file upload cap |
| `CPINTEL_FILES_MAX_TOTAL_BYTES` | `33554432` | Per-user vault cap |
| `CPINTEL_STANDINGS_REFRESH_MS` | `180000` | How often live group boards are rebuilt |
| `CPINTEL_RATE_LIMIT_ENABLED` | `true` | Per-account and per-address throttling on sign-in, runs and syncs |
| `CPINTEL_MIN_DESKTOP_VERSION` | `0.0.0` | Desktop builds below this are told to update, with a ribbon that cannot be closed |
| `CPINTEL_DESKTOP_BUILD` | `1` | `deploy.sh` builds the desktop installers for `CPINTEL_PUBLIC_URL` when that address or the app changed; `0` skips it ([desktop-app.md](desktop-app.md#publishing-the-desktop-app)) |
| `CPINTEL_DESKTOP_REPOSITORY` | *(unset)* | GitHub `owner/name` whose Releases carry the desktop installers; feeds `/download` and the update ribbon when the server has no build of its own |
| `CPINTEL_DESKTOP_GITHUB_TOKEN` | *(unset)* | Only for a private repository: read access to it; downloads are then streamed through the server |
| `CPINTEL_RUNNER_EXAM_ONLY` | `true` | The server runner takes examination runs only; elsewhere code runs on the student's computer |
| `CPINTEL_DOMJUDGE_CREDENTIAL_KEY` | *(unset)* | **Required; the prod profile refuses to start without it.** AES-256-GCM key for the per-contestant, per-classroom DOMjudge logins and for classroom service-account passwords |
| `CPINTEL_PROCTOR_HEARTBEAT_TTL` | `45` | How long one monitoring heartbeat vouches for. Submissions into a monitored examination are refused without a fresh one |
| `CPINTEL_PROCTOR_HEARTBEAT_INTERVAL` | `15` | How often the page sends one |
| `CPINTEL_EXAM_PASSWORD_KEY` | *(unset)* | **Required for examinations; the prod profile refuses to start without it.** AES-256-GCM key for each candidate's examination sign-in password and the optional room password. Without it no sign-in password can be issued, so no examination can be sat |
| `CPINTEL_EXAM_PASSWORD_LENGTH` | `8` | Characters per generated code, grouped in fours for reading off paper |
| `CPINTEL_EXAM_RETENTION_DAYS` | `365` | How long an examination's session log is kept. `0` keeps everything. Swept daily, never at startup |
| `CPINTEL_RUNNER_COMPILE_LIMIT_MS` | `20000` | Wall clock for the compile step |
| `CPINTEL_FILES_MAX_FILES` | `100` | Files per user vault |
| `CPINTEL_SESSION_TTL_DAYS` | `14` | How long a stored Codeforces session is kept |
| `CPINTEL_GAUNTLET_RETAKE_DAYS` | `7` | How soon the placement gauntlet may be retaken |
| `CPINTEL_LAB_NETWORKS` | *(unset)* | Comma-separated CIDRs of labs behind one NAT address; inside them the per-address sign-in limit is raised to a shared one (the per-account limit is unchanged) |
| `CPINTEL_CORS_ALLOWED_ORIGINS` | *(empty)* | **Leave empty when the frontend and API share nginx**, as every CPIntel deployment does: the page, the desktop app and the API are then one origin, on any host or port, and CORS never applies. Only for a page served from a different origin that must call the API: exact `scheme://host[:port]` origins, comma-separated. Wildcards, `null`, paths (even a trailing `/`) and non-http(s) schemes make the `prod` profile refuse to start; elsewhere they are dropped with a warning. Nothing is ever allowed by default |
| `SMTP_HOST` | *(unset)* | Mail server for password resets. **Blank is supported**: resets then reach nobody unless `CPINTEL_MAIL_LOG_UNDELIVERED` is on |
| `SMTP_PORT` / `_USER` / `_PASSWORD` | `587` / — / — | The rest of the SMTP connection |
| `SMTP_AUTH` / `SMTP_STARTTLS` | `true` / `true` | Turn off for a relay that wants neither |
| `CPINTEL_MAIL_FROM` | `CPIntel <no-reply@cpintel.local>` | The From address on anything sent |
| `CPINTEL_MAIL_LOG_UNDELIVERED` | `false` | With no `SMTP_HOST`: `true` writes each undelivered message whole, reset link included, to the backend log for an operator to pass on. Anything else, a typo included, is off |
| `CPINTEL_ALERT_EMAIL` | `CPINTEL_ADMIN_EMAIL` | Where the monitoring profile's Alertmanager emails firing alerts, through the SMTP server above |
| `CPINTEL_PUBLIC_URL` | `http://localhost:5173` | Where a link in an email points — the address people open CPIntel at, not the backend's own |
| `CPINTEL_RESET_TOKEN_MINUTES` | `5` | How long a password reset link is worth |

## Secrets

There are no credential defaults. Every secret — the database passwords, the JWT signing key and
the Codeforces session key — must come from the environment, and under the `prod` profile the
application **refuses to start** if any is missing, too short, or still set to one of the
placeholder values that used to ship as defaults. It reports every problem at once rather than
one restart at a time.

That refusal is the point. Until it existed, a deploy that forgot `JWT_SECRET` booted
successfully and signed tokens with a key published in this repository, which anyone could use
to mint a `SUPER_ADMIN` token. Failing to start is the correct outcome; booting insecure is not.

```bash
# generate a value for JWT_SECRET (min 32 chars) or CPINTEL_SESSION_KEY (min 16)
head -c 48 /dev/urandom | base64 | tr -d '\n=' | tr '+/' '-_'
```

Outside `prod` the same problems are logged as warnings and startup continues, so a half-filled
`.env` gives you a readable explanation rather than a refusal.

Secrets reach the containers as environment variables and nowhere else: not on a command line
(Redis reads its password as a config line on its standard input; the scripts here sign in to
MongoDB from inside the script `mongosh` runs, or through a private `--config` file for
`mongodump`/`mongorestore`), and not in a log. `scripts/test_compose_secrets.py` checks that no
service's command or health check carries one. A container's environment is still visible to
`docker inspect`, so anyone who can run `docker` on the host - root and the `docker` group - can
read them; that is the boundary.

The Postgres, MongoDB and Redis ports are published to `127.0.0.1` only. They are exposed at all
so the backend can run on the host during development; nothing outside the machine needs to reach
a datastore directly.


Every encrypted value (DOMjudge logins, classroom service passwords, examination passwords and
codes, Codeforces sessions) is stored as `v1:` followed by the ciphertext. The prefix names the
key that sealed it, so a key can be rotated later: a new key becomes `v2` and old values are
re-sealed, with nothing guessing which key a value belongs to. Losing a key still means those
values are gone, so keep a copy of the keys off the server.
