# CPIntel

Competitive Programming Intelligence Platform. It aggregates a user's activity across
Codeforces, LeetCode and CodeChef, computes topic mastery, contest analytics, spaced-repetition
revision schedules and an adaptive skill roadmap — and then gives them somewhere to actually
practise and compete, with an editor, a local runner, and admin-run group contests laid over
Codeforces or DOMjudge.

## Why this exists

Most competitive programmers track progress across three or four disconnected platforms with no
unified view of strengths, weak topics, or what to practise next. CPIntel pulls raw submission
history into one place and runs it through an analytics engine (Java services over PostgreSQL)
to produce a single mastery score per topic, decay-aware revision reminders, and a real
dependency graph of DSA sub-skills with Codeforces problems attached to each node.

Around that sits the part you use during a session: a split-pane workspace with the statement on
one side and an editor on the other, a runner that checks your solution against the samples
before you spend a real submission, and — for teachers and team leads — groups whose members can
be ranked against each other out of a public contest's board.

## Architecture

React (Vite, TypeScript) talks to Spring Boot 3 / Java 21, which talks to PostgreSQL (relational
system of record, via JPA), MongoDB (raw submissions, personal files), Redis (cache, sessions,
token revocation), and the external judges (Codeforces / LeetCode / CodeChef / DOMjudge).

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the system diagram, the ER diagram and
the reasoning behind the harder decisions, and [`docs/SRS.md`](docs/SRS.md) for requirements.

## Stack

| Layer | Technology |
|---|---|
| Frontend | React 19, TypeScript, Vite 5, TailwindCSS, Recharts, Zustand, TanStack Query |
| Backend | Spring Boot 3, Java 21, Spring Security, JWT, Spring Data JPA |
| Primary DB | PostgreSQL 16 — relational system of record; scoring runs in the Java service layer |
| Document DB | MongoDB — raw submission history, personal files, contest snapshots |
| Cache | Redis — sessions, JWT blacklist, per-user token revocation, analytics cache |
| Integrations | Codeforces REST + scraping, LeetCode GraphQL, CodeChef (Jsoup), DOMjudge REST v4 |
| Migrations | Flyway — 8 versioned migrations (schema, indexes, materialized views, audit indexes, groups, super admin role, scheduler locks, node-level mastery) |
| Containerization | Docker Compose — 9 services, 3 of them behind the `monitoring` profile |
| Editor | Monaco, bundled locally (no CDN) — shared by the Practice and Compete workspaces |
| Local runner | g++ under bubblewrap + rlimits, judged against the statement's sample tests |
| Desktop | Electron wrapper around the same React SPA, plus contest monitoring |
| Monitoring | Prometheus, Grafana, Loki (optional profile) |

## Features

### Analytics and planning

- Link Codeforces, LeetCode and CodeChef accounts; async background sync of full submission
  history
- Topic mastery engine: accuracy, volume, recency-decay and confidence scored per topic in
  `ScoringFormulas`
- Contest analytics: rating history, wrong-submission patterns, behavioural insight generation
- Unified cross-platform rating, normalised and weighted per platform
- Daily/weekly recommendation sheets and an SM-2-style spaced repetition revision queue
- 35-node adaptive roadmap with real prerequisite chains, live-pulled Codeforces problems per
  node, and auto-unlock based on measured mastery
- Dashboards: topic radar, mastery heatmap, rating-over-time, recent contests

### Practising and competing

- A split-pane workspace on both Practice and Compete — statement and problem list on the left,
  editor above a testcase console on the right, with draggable dividers whose positions are
  remembered
- Sample tests are seeded as **editable** cases; add your own to run the program on anything,
  and Run executes them all
- A Run button that compiles your solution locally and checks it against the samples before you
  spend a real submission — see [Running code locally](#running-code-locally)
- Submit straight to the judge from the editor, with the verdict polled back into the console —
  Codeforces and DOMjudge both, from the same page
- **DOMjudge contests need nothing set up per contestant.** CPIntel submits on each team's
  behalf through one admin API account, so nobody types a judge password; statements are the
  problem package's PDF, embedded, with its sample data seeded into the console
- Submitting to Codeforces uses a session you create yourself in your own browser — the desktop
  app signs you in inside a window it owns, the web build uses a local helper. Nothing anywhere
  asks for your Codeforces password; see [Connecting Codeforces](#connecting-codeforces)
- Submission history with the failing test attached, and an archive of everything you sent
- Personal files: upload your own templates, snippet headers and notebook PDFs, and open them
  from inside a running contest without leaving the page

### Group contests

- An admin lays a group over a contest running on Codeforces or DOMjudge and gets the group's
  own ranking out of it — where each member placed among the people they are actually being
  measured against, rather than among the thousands on the judge's board
- Members see their own placing; whether the full board is published to them is the admin's call
- Contest monitoring (desktop **and** browser): while a round is running, leaving the contest
  window is noticed. The contestant is warned after ten seconds away — a native notification on
  the desktop, a flashing tab title in the browser — and absences past that threshold are
  reported to whoever runs the group. Briefer glances are counted but never escalated

### Administration

- JWT auth with refresh token rotation and Argon2id password hashing
- Three roles — `USER`, `ADMIN`, `SUPER_ADMIN` — with role assignment and account creation
  reserved to the super admin, so the tier that hands out privilege sits above the tier that
  uses it. Self-registration is closed by default; see [Roles and accounts](#roles-and-accounts)
- Admin console at `/admin`: deployment overview, account administration (roles, deactivation,
  signing an account out everywhere), an append-only audit trail of every sign-in and
  administrative change, group and contest management, and per-contest control of personal
  file access

## Running locally

### Prerequisites

Docker, Java 21, Node 20, Maven (or use the included `mvnw`).

### 1. Environment

```bash
cp .env.example .env
# fill in DB passwords and a JWT secret
```

### 2. Start the databases

```bash
docker compose up postgres mongodb redis -d
docker compose ps postgres   # STATUS should read (healthy)
```

### 3. Backend

```bash
cd backend
./mvnw spring-boot:run
```

Flyway applies all migrations automatically on startup.

### 4. Frontend

```bash
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173`.

### Full Docker stack (production-style)

```bash
docker compose up -d --build     # open http://localhost
```

### Monitoring (optional)

```bash
docker compose --profile monitoring up -d
```

Grafana on `:3000`, Prometheus on `:9090`.

## Configuration

Everything below has a working default; only the secrets in `.env` genuinely need setting.

| Variable | Default | What it does |
|---|---|---|
| `CPINTEL_REGISTRATION_ENABLED` | `false` | Whether anyone may create their own account. Off: a super admin creates them |
| `CPINTEL_ADMIN_EMAIL` | *(unset)* | The super administrator — see [Roles and accounts](#roles-and-accounts) |
| `CPINTEL_ADMIN_PASSWORD` | *(unset)* | Only read when that address matches no account, to create one |
| `CPINTEL_ADMIN_USERNAME` | `superadmin` | Username for that created account |
| `CPINTEL_RUNNER_ENABLED` | `true` (off in `prod`) | Whether code may be compiled and run on the backend host |
| `CPINTEL_RUNNER_TIME_LIMIT_MS` | `5000` | Wall clock per test case |
| `CPINTEL_RUNNER_MEMORY_MB` | `512` | Address-space limit per run |
| `CPINTEL_SUBMIT_ENABLED` | `true` | Whether submissions may be forwarded to Codeforces |
| `CPINTEL_SESSION_KEY` | *(none — required)* | Encrypts stored Codeforces session cookies. At least 16 chars |
| `CPINTEL_FILES_IN_CONTEST` | `true` | Whether contests offer personal files by default |
| `CPINTEL_FILES_MAX_FILE_BYTES` | `2097152` | Per-file upload cap |
| `CPINTEL_FILES_MAX_TOTAL_BYTES` | `33554432` | Per-user vault cap |
| `CPINTEL_STANDINGS_REFRESH_MS` | `180000` | How often live group boards are rebuilt |
| `CPINTEL_RATE_LIMIT_ENABLED` | `true` | Per-account and per-address throttling on sign-in, runs and syncs |
| `CPINTEL_MIN_DESKTOP_VERSION` | `0.0.0` | Desktop builds below this are told to update |
| `CPINTEL_DOMJUDGE_URL` | *(unset)* | Base URL of a DOMjudge instance; DOMjudge contests do nothing until this is set |
| `CPINTEL_DOMJUDGE_USER` / `_PASSWORD` | *(unset)* | Needs **admin** rights to submit on a team's behalf. A read-only account reads scoreboards fine and then refuses every submission |

### Secrets

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

The Postgres, MongoDB and Redis ports are published to `127.0.0.1` only. They are exposed at all
so the backend can run on the host during development; nothing outside the machine needs to reach
a datastore directly.

### Roles and accounts

Three tiers:

| Role | Can do |
|---|---|
| `USER` | The product. No console. |
| `ADMIN` | The console: accounts, groups, audit trail, contest file policy. Can activate and deactivate accounts. |
| `SUPER_ADMIN` | Everything an admin can, **plus** assigning roles and creating accounts. |

Role assignment is deliberately kept out of `ADMIN`. An admin who can promote other admins can
promote themselves past any limit placed on them, so the tier that hands out privilege sits
above the tier that uses it. Both halves are enforced server-side — the UI hides what you
cannot use, but a hand-written request from an ordinary admin gets a 403, not data.

**Self-registration is off by default.** There is no sign-up page; accounts are created by a
super admin from `/admin/users`. Set `CPINTEL_REGISTRATION_ENABLED=true` to reopen public
sign-up, and the `/api/v1/auth/register` endpoint starts accepting again.

#### The first super admin

Nothing inside the product can create one — that is the point, since it would make the top of
the permission tree reachable from a screen. It is named in the environment instead:

```bash
CPINTEL_ADMIN_EMAIL=you@example.com
# only needed if no account has that address yet
CPINTEL_ADMIN_PASSWORD=something-long-and-yours
```

On the next start, an existing account with that address is promoted to `SUPER_ADMIN`; if none
exists, one is created — but only when a password is also supplied. There is deliberately no
default administrator password anywhere in this deployment, because a well-known one is the
most reliable way to end up compromised.

It is idempotent and safe to leave set. It is also the way back in if the last admin is ever
locked out, since it reactivates the named account as well as promoting it. A super admin
cannot be demoted or deactivated from the console, so moving that role means changing this
setting and restarting.

### Connecting Codeforces

Linking a handle on `/platforms` is what reads your public history. Submitting from CPIntel
needs something further: a Codeforces *session*. Both live on the Codeforces row of that page.

Codeforces publishes no login API, and it serves its pages only to a client that has passed a
browser check. So CPIntel never asks for your password and never signs in on your behalf — it
reuses a session you created yourself, in a real browser.

**Desktop app.** Press *Sign in to Codeforces*. A window opens on codeforces.com, you sign in
there as you would anywhere, and the app reads the cookies of the session that window created
and hands them to the backend. The cookie never passes through the page, and no other browser
on the machine is touched.

**Web build.** A page cannot read an HttpOnly cookie belonging to another origin, so the session
has to be fetched by something outside the browser. Run the helper, then press *Connect*:

```bash
./scripts/grab-cf-cookie.py --serve
```

It reads Codeforces cookies from whichever browser you are signed in with, posts them straight
to the CPIntel backend, and answers the page with nothing but the handle it connected. A manual
route — paste the `Cookie` request header from DevTools — is behind the same card if the helper
cannot run.

**Sessions go stale, and sooner than they look.** The cookie recording that you passed the
browser check lives hours; the session CPIntel stores lives a fortnight. When it expires, the
account still reads as connected while statements and submits start failing. Problem pages say
so and link back to `/platforms` to reconnect; the desktop app usually needs one click, since it
keeps you signed in to Codeforces in its own window.

The stored cookie is encrypted at rest with `CPINTEL_SESSION_KEY`, kept in Redis only, never
returned by any endpoint, and dropped by *Disconnect* here or from Codeforces' own
Settings -> Sessions.

### Running a group contest

1. `/admin/groups` — create a group and add its members. For a whole class, the **Import a
   roster** panel on the group page takes a CSV file or a range pasted straight out of Excel or
   Sheets (that paste is tab-separated, and the parser detects which it is given). The first row
   names the columns; `email` or `username` is required, `fullName`, `cfHandle` and `teamName`
   optional. **Preview** first — it writes nothing and reports per row what would happen. Rows
   for people who have no account yet create one, which only a `SUPER_ADMIN` may do: a plain
   admin is refused with the count before anything is written. Generated passwords are shown
   once, on that screen, and stored hashed — the download is the only copy.
2. Set each member's handle on the judge. Codeforces members are found through their linked
   platform account automatically; DOMjudge members need their team name typed in, because
   CPIntel has no other way to identify them.
3. Add a contest to the group: the judge, the contest id, and the **start and end times**. Those
   times decide when monitoring is expected and which reports are accepted, so they should match
   the judge's own window.
4. Members see it under `/groups`, and sit it on `/compete` as normal — pick the judge, paste
   the contest id, and the statements, editor, submissions and live rank all appear there.
5. `/admin/groups/contests/{id}` shows the group's ranking and what the monitors reported.
   Standings refresh on a timer while the contest is live, or on demand.

#### Rehearsing with 200 simulated contestants

`scripts/simulation/` builds a whole fake contest — six problems as DOMjudge-importable
packages, 200 participants wired into both systems, and a driver that plays all 200 at once
and reports latency percentiles, error rates and submit-to-verdict times.

```bash
./scripts/simulation/selftest.py          # prove the harness works, needs nothing running
./scripts/simulation/make_problemset.py --verify
./scripts/simulation/make_roster.py --count 200
```

See [`scripts/simulation/README.md`](scripts/simulation/README.md) for the rest. Worth doing
before the first real round: it is the only way to find out what 200 people actually cost
before 200 people are in the room.

#### Before a DOMjudge round

Run the probe against the instance first — it confirms the things that fail late and loudly
otherwise:

```bash
DJ_URL=https://judge.example.edu DJ_USER=admin DJ_PASS=... ./scripts/domjudge-probe.sh
```

It reports the API version, whether the account can submit on a team's behalf, and which
sample-testcase route this DOMjudge build exposes — that last one moved between releases, so
CPIntel probes for it at runtime and the script tells you the answer up front.

Two things are worth checking by hand as well:

- **Team names must match.** A member's `externalHandle` is matched against the DOMjudge team's
  `name` or `display_name`, case- and whitespace-insensitively. A mismatch shows as *unmatched*
  on the board and as "you are not registered as a team" in the arena — not as an error.
- **Scale.** DOMjudge reads are shared: one fetch of the submissions, scoreboard and contest
  state serves every contestant, so 200 people polling cost the judge the same as one. This is
  unlike the Codeforces path, which is bounded by that judge's public rate limit.

## Where things are in the app

| Route | Who | What |
|---|---|---|
| `/dashboard` | anyone | Topic radar, streak, recent contests |
| `/analytics` | anyone | Mastery heatmap, rating history, behavioural insights |
| `/recommendations` | anyone | Daily sheet, weekly plan, revision queue |
| `/roadmap` | anyone | The 35-node dependency graph with problems per node |
| `/practice` | anyone | Problem search, statement, editor, local runner, submit |
| `/compete` | anyone | A live contest: statements, editor, submissions, rank, monitoring |
| `/platforms` | anyone | Link and sync Codeforces / LeetCode / CodeChef; connect the Codeforces session used for submitting |
| `/groups` | anyone | Group contests you are in, and where you placed |
| `/profile` | anyone | Your account |
| `/admin` | admin | Deployment overview and recent activity |
| `/admin/users` | admin | Accounts, roles, deactivation, session revocation |
| `/admin/audit` | admin | The append-only audit trail |
| `/admin/groups` | admin | Groups, members, contests, standings, monitor reports |
| `/admin/contest-files` | admin | Which contests may offer personal files |

## API documentation

Swagger UI: `http://localhost:8080/swagger-ui.html`

### API versioning

Every route lives under `/api/v1`. The version is in the path rather than a header so that a
request can be read, logged and curl'd without extra ceremony, and so a proxy can route on it.

`GET /api/version` is unversioned and needs no authentication — it reports the current and
minimum API versions plus the minimum desktop build the server will accept. The browser SPA is
served from the same origin and cannot disagree with the API, but the desktop app is packaged
and installed separately, so it can be arbitrarily far behind; this is how it finds out before
it fails on a route that no longer exists.

Everything under `/api/admin/**` requires the ADMIN role, enforced in the security filter chain
as well as on each controller.

## Tests

```bash
cd backend  && ./mvnw test      # 304 tests
cd electron && npm test         # away-time accounting
cd frontend && npm run type-check
```

The frontend has no test runner yet; `npm run lint` is defined but has no ESLint config, so it
currently fails. Both are known gaps rather than oversights.

## Desktop app

Electron dev mode does **not** spawn its own backend or bundle the frontend — it opens a window
pointed at your already-running Vite dev server. Start the backend and frontend as above, then:

```bash
cd electron
npm install
npm run dev
```

If you see `Unable to access jarfile .../resources/backend/app.jar`, `NODE_ENV` isn't set to
`development` — the `dev` script must run Electron with `NODE_ENV=development` (via `cross-env`)
so `main.ts` skips spawning the packaged backend.

For a production desktop build, which spawns the bundled backend jar and loads the built
frontend instead of Vite:

```bash
cd backend  && ./mvnw clean package -DskipTests && cd ..
cd frontend && npm run build && cd ..
cd electron && npm run build
```

The packaged app, backend jar and frontend dist are bundled together as Electron resources (see
`extraResources` in `electron/package.json`). The Electron shell wraps the same React SPA used on
web — there is no separate frontend codebase.

The desktop build is also where contest monitoring is strongest: the away-time accounting lives
in the Electron main process, where the page cannot reach it. The browser build does the same
job with the Page Visibility API and says plainly that it is the weaker kind.

It is also the easier place to connect Codeforces: the app signs you in through a window it owns
rather than asking you to run a helper, and the sign-in persists, so reconnecting an expired
session is one click. See [Connecting Codeforces](#connecting-codeforces). Connecting is refused
while a contest lockdown is engaged — a full browser window mid-round would walk around
everything the lock is doing, so it is something to do before the round starts.

## Running code locally

The Run button compiles and executes your solution **on the machine hosting the backend**, then
compares stdout against each test case. It is a rehearsal harness, not a judge: passing the
samples means your code builds and agrees with the examples, nothing more — Codeforces tests far
more than that, and a problem with several valid answers will show a mismatch here even when the
answer is right.

Execution is sandboxed with [bubblewrap](https://github.com/containers/bubblewrap): only `/usr`
is mounted, read-only, so `/home`, `/etc` and `/var` do not exist inside, and all namespaces are
unshared, which takes the network with them. On top of that every run gets a wall-clock timeout
and CPU, address-space and output-size limits. `GET /api/run/status` reports whether the sandbox
is actually in effect; if bubblewrap is missing, runs still get the resource limits but not the
isolation, and the UI says so.

**This is arbitrary code execution by design.** It defaults on for dev and the desktop build,
where the only code that runs is yours. `application-prod.yml` turns it off, because on a shared
deployment it would hand every account a shell. Override with `CPINTEL_RUNNER_ENABLED`, and only
behind a sandbox you trust.

C++ is the only language wired up so far. Adding Python, Java or SQL means implementing
`LanguageRuntime` — one class, no other changes; see the notes on that interface for how SQL
differs from the others.

## Repository structure

```
backend/    Spring Boot API, Flyway migrations, analytics engine, integrations
frontend/   React SPA — pages, the shared workspace components, hooks
electron/   Desktop wrapper and contest monitoring
nginx/      Reverse proxy config
monitoring/ Prometheus/Grafana/Loki config
docs/       Architecture, requirements and feature notes
scripts/    Dev convenience scripts
```

## License

MIT
