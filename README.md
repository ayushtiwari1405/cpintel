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
| Migrations | Flyway — 10 versioned migrations (schema, indexes, materialized views, audit indexes, groups, super admin role, scheduler locks, node-level mastery, examinations, examination access) |
| Containerization | Docker Compose — 9 services, 3 of them behind the `monitoring` profile |
| Editor | Monaco, bundled locally (no CDN) — shared by the Practice, Compete and examination workspaces |
| Local runner | g++ and CPython under bubblewrap + rlimits, judged against the statement's sample tests |
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

Practice is open at any time. The **Compete** section has two halves beside it: *Contests*,
which anyone may open, and *Examinations*, which are assigned to you.

- A split-pane workspace on Practice, Compete and examinations alike — statement and problem
  list on the left, editor above a testcase console on the right, with draggable dividers whose
  positions are remembered
- Sample tests are seeded as **editable** cases; add your own to run the program on anything,
  and Run executes them all
- A Run button that compiles your solution locally and checks it against the samples before you
  spend a real submission — see [Running code locally](#running-code-locally)
- Submit straight to the judge from the editor, with the verdict polled back into the console —
  Codeforces and DOMjudge both, from the same page
- **DOMjudge contestants never type a judge password.** An admin attaches each person's
  DOMjudge login once, from the group's member table; the contestant then signs into CPIntel as
  usual and the arena competes as them, so the judge attributes every submission to their own
  team. They pick their round from a list of the contests DOMjudge says they are registered
  for, rather than hunting for a contest id. Statements are the problem package's PDF,
  embedded, with its sample data seeded into the console
- **A leaderboard in the sidebar**, on DOMjudge: the whole contest board as the judge ranks it,
  with your own team pinned above it and broken out per problem. Every number is the judge's
  own, so it agrees with the board on the wall
- Submitting to Codeforces uses a session you create yourself in your own browser — the desktop
  app signs you in inside a window it owns, the web build uses a local helper. Nothing anywhere
  asks for your Codeforces password; see [Connecting Codeforces](#connecting-codeforces)
- Submission history with the failing test attached, and an archive of everything you sent
- Personal files: upload your own templates, snippet headers and notebook PDFs, and open them
  from inside a running contest without leaving the page

### Teams and team contests

- An admin lays a team over a contest running on Codeforces or DOMjudge and gets the team's
  own ranking out of it — where each member placed among the people they are actually being
  measured against, rather than among the thousands on the judge's board
- Members see their own placing; whether the full board is published to them is the admin's call
- **A team contest is not monitored.** It is practice among people who chose to enter it, and
  nothing watches one — no away timer, no focus log, no password at the door, and no submission
  ever refused for want of a heartbeat. Monitoring is what makes an examination an examination;
  a round that needs invigilating is created as one, from `/admin/exams`
- Per-team analytics: participation rate, average solved, best placing, and the recent events
  the team sat

### Examinations

An examination is a DOMjudge contest with CPIntel's invigilation around it. It differs from an
ordinary contest in four ways, and nothing else:

- **It is assigned, never public.** Teams, named individuals, or both. Somebody named twice
  counts once, and a candidate sees only the examinations that are theirs
- **It has a life of its own**: `DRAFT → SCHEDULED → ACTIVE → ENDED → ARCHIVED`. A draft is
  invisible to candidates however close its start time is; the three middle states follow the
  clock, so nobody has to remember to press anything for a paper to open
- **It is opened with a password handed out in the room, and only while it is running.** Being
  on the roster is not enough: an assignment was made a fortnight ago and cannot say somebody is
  in the room, and the clock says the paper is open but not that it is open *for this person,
  here*. So an admin generates two things — one **examination password** for the whole paper,
  read out when the invigilator starts it, and one **personal code** per candidate, printed on
  the slip on their desk. Neither is ever emailed. Until both are given, the candidate sees the
  clock and the rules and nothing else, not even the problem list
- **It is monitored, and the candidate is told so on the screen where it happens.** Focus
  losses, returns, absences past the paper's own away threshold, problems opened, submissions
  and verdicts are all recorded. The threshold and the desktop restrictions are set per
  examination. **Only examinations are monitored** — an ordinary contest is practice among
  people who chose to enter it, and nothing watches one
- **It leaves a log.** Admins get a live monitoring dashboard while it runs — who is present,
  who is away and for how long, what each candidate was last seen doing — and afterwards a
  session log filterable by person, team, event type and time, kept for a configurable
  retention period
- **Afterwards, candidates get their own code back.** A paper that has ended opens onto the
  source of every submission that candidate made into it, read out of CPIntel's own archive
  rather than fetched from the judge. Their code, and nothing else: not their marks, not
  anybody else's verdicts, not the test data — results are published by whoever decides to
  publish them, and a review screen that quietly became a results screen would take that
  decision away from them

Everything on both screens is an observation rather than a finding. A focus loss is a focus
loss; whether it was a second screen, a notification or somebody answering the door is not
something software can know, which is why there is no single "suspicion score" anywhere in
the product.

### Administration

- JWT auth with refresh token rotation and Argon2id password hashing
- Three roles — `USER`, `ADMIN`, `SUPER_ADMIN` — with role assignment, the creation of other
  admins, and account deletion reserved to the super admin, so the tier that hands out
  privilege sits above the tier that uses it. An ordinary admin runs the room but cannot
  deactivate, edit or re-password another administrator. Self-registration is closed by
  default; see [Roles and accounts](#roles-and-accounts)
- Sign in with a username or an email address; set your own password from your profile, or
  reset it through a one-use emailed link. The first password is handed over with the username
  and never mailed — see [Passwords](#passwords)
- Admin console at `/admin`: deployment overview, account administration (roles, profile
  corrections, deactivation, password resets, signing an account out everywhere, participation
  history), an append-only audit trail of every sign-in and administrative change, team
  management (including moving somebody between teams in one step), contests and examinations
  with their rosters, problems, passwords, monitoring and logs, and per-contest control of
  personal file access

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
| `CPINTEL_REGISTRATION_ENABLED` | `false` | Whether anyone may create their own account. Off: an admin creates them |
| `CPINTEL_ADMIN_EMAIL` | *(unset)* | The super administrator — see [Roles and accounts](#roles-and-accounts) |
| `CPINTEL_ADMIN_PASSWORD` | *(unset)* | Only read when that address matches no account, to create one |
| `CPINTEL_ADMIN_USERNAME` | `superadmin` | Username for that created account |
| `CPINTEL_RUNNER_ENABLED` | `true` (off in `prod`) | Whether code may be compiled and run on the backend host |
| `CPINTEL_RUNNER_TIME_LIMIT_MS` | `5000` | Wall clock per test case |
| `CPINTEL_RUNNER_MEMORY_MB` | `512` | Address-space limit per run, for every language |
| `CPINTEL_RUNNER_CPP` | `g++` | C++ compiler to use |
| `CPINTEL_RUNNER_PYTHON` | `python3` | Python interpreter to use |
| `CPINTEL_SUBMIT_ENABLED` | `true` | Whether submissions may be forwarded to Codeforces |
| `CPINTEL_SESSION_KEY` | *(none — required)* | Encrypts stored Codeforces session cookies. At least 16 chars |
| `CPINTEL_FILES_IN_CONTEST` | `true` | Whether contests offer personal files by default |
| `CPINTEL_FILES_MAX_FILE_BYTES` | `2097152` | Per-file upload cap |
| `CPINTEL_FILES_MAX_TOTAL_BYTES` | `33554432` | Per-user vault cap |
| `CPINTEL_STANDINGS_REFRESH_MS` | `180000` | How often live group boards are rebuilt |
| `CPINTEL_RATE_LIMIT_ENABLED` | `true` | Per-account and per-address throttling on sign-in, runs and syncs |
| `CPINTEL_MIN_DESKTOP_VERSION` | `0.0.0` | Desktop builds below this are told to update |
| `CPINTEL_DOMJUDGE_URL` | *(unset)* | Base URL of a DOMjudge instance; DOMjudge contests do nothing until this is set |
| `CPINTEL_DOMJUDGE_USER` / `_PASSWORD` | *(unset)* | **Optional** service account. Not needed to run a contest — it buys the contest-wide reads that let one fetch serve the whole room. Without it, each contestant's page reads the judge under their own credentials |
| `CPINTEL_DOMJUDGE_CREDENTIAL_KEY` | *(unset)* | **Required to attach contestants' accounts.** AES-256-GCM key for the per-contestant DOMjudge logins |
| `CPINTEL_DOMJUDGE_CREDENTIAL_TTL_DAYS` | `30` | Attached credentials expire on their own, so a round nobody cleaned up after does not leave passwords on file |
| `CPINTEL_PROCTOR_HEARTBEAT_TTL` | `45` | How long one monitoring heartbeat vouches for. Submissions into a monitored examination are refused without a fresh one |
| `CPINTEL_PROCTOR_HEARTBEAT_INTERVAL` | `15` | How often the page sends one |
| `CPINTEL_EXAM_PASSWORD_KEY` | *(unset)* | **Required to put a password on an examination.** AES-256-GCM key for the password read out to the room and each candidate's own code. Without it, an examination opens for anyone assigned to it |
| `CPINTEL_EXAM_PASSWORD_LENGTH` | `8` | Characters per generated code, grouped in fours for reading off paper |
| `SMTP_HOST` | *(unset)* | Mail server for password resets. **Blank is supported** — the message is logged instead, which is what a closed exam-lab network needs |
| `SMTP_PORT` / `_USER` / `_PASSWORD` | `587` / — / — | The rest of the SMTP connection |
| `SMTP_AUTH` / `SMTP_STARTTLS` | `true` / `true` | Turn off for a relay that wants neither |
| `CPINTEL_MAIL_FROM` | `CPIntel <no-reply@cpintel.local>` | The From address on anything sent |
| `CPINTEL_PUBLIC_URL` | `http://localhost:5173` | Where a link in an email points — the address people open CPIntel at, not the backend's own |
| `CPINTEL_RESET_TOKEN_MINUTES` | `60` | How long a password reset link is worth |

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
| `ADMIN` | The console: accounts, groups, audit trail, contest file policy. Can activate and deactivate accounts, and **create ordinary users** — singly or by roster import. |
| `SUPER_ADMIN` | Everything an admin can, **plus** assigning roles and creating other admins. |

Role assignment is deliberately kept out of `ADMIN`, and so is creating an `ADMIN`. An admin who
can promote other admins can promote themselves past any limit placed on them — and one who
could *create* an admin could create one for themselves and hold two accounts, which is the same
escalation spelled differently. So the tier that hands out privilege sits above the tier that
uses it.

Creating an ordinary user is not that, and is an admin's job: somebody running a contest has to
be able to add the people sitting it, and routing every new participant through a super admin
turns a class list into an escalation request. The same applies to roster imports, which are
safe for a different and more specific reason — **every account an import creates is a `USER`,
with no way for a row to say otherwise**. If that ever changes, the permission has to move back.

All of it is enforced server-side — the UI hides what you cannot use, but a hand-written request
from an ordinary admin trying to create an admin gets a 403, not an account.

**Self-registration is off by default.** There is no sign-up page; accounts are created by a
super admin from `/admin/users`. Set `CPINTEL_REGISTRATION_ENABLED=true` to reopen public
sign-up, and the `/api/v1/auth/register` endpoint starts accepting again.

**An admin may not touch another admin's account.** Editing details, deactivating, signing out
and setting a password are all refused for a target who holds `ADMIN` or `SUPER_ADMIN` unless
the caller is a super admin. An admin who can deactivate or re-password a peer can remove the
person who would have stopped them, and one who could reach a super admin could remove the tier
that limits them at all — which is the same escalation the role rule exists to prevent, arrived
at from a different screen. A super admin has no such limit: every account in the deployment,
admins included, is theirs to change.

**Deleting an account is a super admin's, and is the blunt instrument.** The cascade takes the
person's submissions, files, standings and examination events with them, so it is refused for
anybody who has sat an examination — their session log is evidence about a paper somebody may
still be marking. Deactivating stops them signing in and leaves all of it readable, which is
what "remove this person" almost always means.

### Passwords

**The first password is handed over, not emailed.** An account is created with a username and a
password, and both are given to the person directly — on a deployment sheet, in a room, however
that particular arrangement works. Nothing is mailed to bootstrap an account, because the mail
would be a live credential sitting in a mailbox and because the networks this runs on cannot
always reach a mail server at the moment the accounts are made.

**After that the password is the owner's.** They can change it from `/profile`, which asks for
the current one — a session can be an unlocked laptop, and that field is what makes the person
at the keyboard the owner rather than whoever sat down after them. If they cannot sign in at
all, **Forgot password?** on the sign-in page mails a link to the address on the account.

The admin user list has a **Password** column showing whether each account still has the
password it was issued. Until somebody changes their own, the administrator who created it
knows it, and a column of "as issued" is a room that has not been asked to change theirs.

A reset link is stored only as a SHA-256 digest, works once, expires within the hour, and
redeeming it signs every device out — because somebody resetting a password they believe is
known to somebody else has not finished until the other party is signed out. Asking for a new
link retires the previous one, so a mailbox with four reset mails in it holds one working link.

`/auth/forgot-password` answers identically whether or not the address belongs to an account.
The endpoint is open to the internet, and an answer that distinguished the two would be a way
to test a list of addresses against the roster of whoever is being examined.

**Signing in takes either a username or an email address**, in one field. People here are given
a username and may never use the address on their account, so insisting on the address would
ask half of them for something they do not have to hand.

**With no SMTP configured, resets still work — for an operator.** The message, including the
link, is written to the backend log at INFO, so a deployment on a closed network can complete a
reset by hand. The backend warns about it at startup rather than refusing, because an exam lab
with no mail server is a real arrangement rather than a mistake. An administrator can also set
a password directly from `/admin/users`, which shows it once and never emails it.

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

### Running a team contest

1. `/admin/teams` — create a team and add its members. For a whole class, the **Import a
   roster** panel on the group page takes a CSV file or a range pasted straight out of Excel or
   Sheets (that paste is tab-separated, and the parser detects which it is given). The first row
   names the columns; `email` or `username` is required, `fullName`, `cfHandle` and `teamName`
   optional. **Preview** first — it writes nothing and reports per row what would happen. Rows
   for people who have no account yet create one; any admin may do this, and the account is
   always an ordinary `USER`. Generated passwords are shown once, on that screen, and stored
   hashed — the download is the only copy.

   **Put everyone on one team** with the team box beside the paste, which is what a class list
   normally wants. A `teamName` on a row still wins over it, so a mixed roster keeps its own
   assignments.
2. Set each member's handle on the judge. Codeforces members are found through their linked
   platform account automatically. For DOMjudge, attach each member's account in the
   **DOMjudge account** column — username, password, their name, and optionally the team you
   want them grouped under. CPIntel verifies the login against the judge and shows the team it
   resolved to, so a wrong account is caught then rather than at the end of the contest.
   Requires `CPINTEL_DOMJUDGE_CREDENTIAL_KEY` to be set.

   **On the team picker.** Leave it blank and the judge's own answer is used, which is right
   whenever the account is already on the correct team. Choosing a team sets how *CPIntel*
   groups that person for its standings — it cannot change where their code lands, because
   DOMjudge reads the team off the login and ignores anything sent with a submission. When
   your choice and the judge's disagree the row says so, with a warning triangle, rather than
   silently picking one: that is sometimes deliberate and usually a typo, and only you can
   tell which. Where an account is attached, the standings board matches on the exact team id
   instead of falling back to name-matching `externalHandle`.
3. Add a contest to the group: the judge, the contest id, and the **start and end times**. Those
   times decide when monitoring is expected and which reports are accepted, so they should match
   the judge's own window.
4. Members see it under `/teams`, and sit it on `/compete` as normal. On Codeforces they
   paste the contest link; on DOMjudge they pick from the list of contests their attached
   account may enter. Statements, editor, submissions, live rank and the board all appear
   there. Nothing about a team contest is monitored or password-protected — see
   [Running an examination](#running-an-examination) for the round that is.
5. `/admin/teams/{id}` lists the team's contests; opening one shows the team's ranking and
   what the monitors reported. Standings refresh on a timer while the contest is live, or on
   demand.

### Running an examination

1. `/admin/exams` — create it. It needs a name, the DOMjudge contest id, and a start and end.
   It is created as a **draft**: invisible to candidates however close its start time is, so
   saving a half-written paper is never the same click as publishing it to two hundred people.
2. **Who sits it** — assign teams, individuals, or both. An examination for four candidates who
   missed the first sitting needs no team at all.
3. **Problems** — the labels, their order and what each is worth. Statements, test data and
   verdicts stay on DOMjudge; duplicating them here would create a second source of truth for
   the one thing the judge is actually authoritative about.
4. **Languages** — which languages the paper accepts. Leave everything unticked, which is the
   default, and it takes whatever the judge offers.

   Ticking some narrows all three places a candidate meets a language: the editor's picker, the
   submit endpoint, and the Run button — the last of these because a rule that held only at
   Submit would hold in the place they touch once and not in the place they touch every two
   minutes.

   The restriction applies to what the judge offers rather than replacing it, so a language
   your DOMjudge contest does not have stays unavailable however it is ticked here. A judge
   language CPIntel cannot recognise is also hidden while a restriction is on: an admin who
   named three languages meant three, and offering a fourth because a judge calls it something
   unfamiliar would quietly overrule them.
5. **Monitoring** — whether it is watched, how long a candidate may be away before it is
   recorded, and which desktop restrictions the examination asks for. Every restriction is a
   request: the desktop build applies what the operating system allows and reports what it
   could not, and a browser applies almost none of them — which is why an examination sat in a
   browser records that its monitoring was the weaker kind rather than showing a clean sheet.
6. **Passwords** — the tab that produces what you carry into the room. **Generate** the
   examination password, which is one string for the whole paper and the thing you read out
   when you start it. Then **issue codes**, which gives every assigned candidate one of their
   own; download the CSV and print the desk slips from it.

   Both are needed because they answer different questions. The shared password says *this
   sitting has begun*; by the time the paper starts everybody in the room has it, so on its own
   it cannot tell a candidate from somebody who heard it read out through a door. The personal
   code says *the person typing this is the person this seat belongs to*.

   **Neither is ever emailed, and there is no route that would.** The credential in somebody's
   mailbox gets them into CPIntel; getting into the paper additionally needs something only you
   can hand them. Mailing an examination password would collapse the two, and a candidate at
   home with a phone would have everything they needed.

   Issue is additive, so a candidate added an hour before the sitting gets a code without
   invalidating the two hundred slips you have already printed. A slip that goes missing at
   eleven is a **Reissue** on that one row. A password that reaches the wrong room is a
   **Rotate**, which — unlike simply telling people the new one — ends every session that was
   opened with the old one.

   Requires `CPINTEL_EXAM_PASSWORD_KEY`. Without it the tab says so, and the examination opens
   for anyone assigned to it.
7. **Publish.** From here the clock owns it: it opens when its window opens and closes when the
   window closes. "End now" moves the window rather than setting a flag, so the arena and this
   screen cannot disagree about whether submissions are open.
8. Candidates find it under **Compete → Examinations** and enter it. If it has a password they
   get one screen asking for what you gave them — it says up front whether it wants one code or
   two, so nobody in a hall with a clock running is guessing at how many boxes to fill. A wrong
   answer does not say which half was wrong, because naming it would turn a pair of secrets
   into two independent guesses.

   Once through, they work in the same workspace a contest uses. They are told they are being
   monitored, told the threshold, and warned while they are away rather than afterwards.
9. **Monitor** and **Session log** tabs, during and after. The log is filterable by candidate,
   team, event type and time, and is kept for `CPINTEL_EXAM_RETENTION_DAYS` days. The
   **Passwords** tab keeps working while the paper runs — reissuing one candidate's code, or
   making somebody unlock again after you move them to another machine.
10. Once it has ended, the same entry under **Compete → Examinations** opens onto the code that
   candidate submitted. Marks are not published by this; that stays yours to decide.

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

Run it once with the service account, if you have one, and once with a contestant's login —
they are allowed to do quite different things, and the script reports both. It names the API
version, what `GET /api/v4/user` says the account's team is, which contest-wide reads the
account may make, whether it can submit with and without an explicit `team_id`, and which
sample-testcase route this DOMjudge build exposes — that last one moved between releases, so
CPIntel probes for it at runtime and the script tells you the answer up front.

Three things are worth checking by hand as well:

- **Each contestant's account must resolve to a team.** Attaching one in the admin console
  verifies this and shows the team; an account with no team (a jury or admin login) is refused
  outright, because submissions made as it would be accepted by the API and land on no board.
- **Team names must match** for the *group standings board*, which is separate from the arena.
  A member's `externalHandle` is matched against the DOMjudge team's `name` or `display_name`,
  case- and whitespace-insensitively. A mismatch shows as *unmatched* on the board — not as an
  error. The arena itself does not use this; it takes the team from the attached account.
- **Scale depends on the service account.** With one, DOMjudge reads are shared: a single fetch
  of the submissions, scoreboard and contest state serves every contestant, so 200 people
  polling cost the judge the same as one. Without one, reads are made as each contestant and
  cached per account — necessary, because DOMjudge filters a team account's view of the
  submissions list to its own team and a shared entry would show contestants each other's
  verdicts. Budget accordingly for a large round.

## Where things are in the app

| Route | Who | What |
|---|---|---|
| `/dashboard` | anyone | Topic radar, streak, recent contests |
| `/analytics` | anyone | Mastery heatmap, rating history, behavioural insights |
| `/recommendations` | anyone | Daily sheet, weekly plan, revision queue |
| `/roadmap` | anyone | The 35-node dependency graph with problems per node |
| `/practice` | anyone | Problem search, statement, editor, local runner, submit |
| `/compete` | anyone | Contests and examinations: statements, editor, submissions, rank, monitoring |
| `/platforms` | anyone | Link and sync Codeforces / LeetCode / CodeChef; connect the Codeforces session used for submitting |
| `/teams` | anyone | Team contests you are in, and where you placed |
| `/profile` | anyone | Your account |
| `/admin` | admin | Deployment overview and recent activity |
| `/admin/users` | admin | Accounts, roles, deactivation, session revocation |
| `/admin/audit` | admin | The append-only audit trail |
| `/admin/teams` | admin | Teams, members, contests, standings, monitor reports, team analytics |
| `/admin/exams` | admin | Contests and examinations: rosters, problems, lifecycle |
| `/admin/exams/{id}` | admin | One event — settings, who sits it, live monitoring, session log |
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

### Languages

**C++** (`g++`, `-std=c++20 -O2 -m64`, statically linked so the binary does not need shared
libraries visible inside the sandbox) and **Python 3** (CPython). Each toolchain is probed on
`PATH` per request, so installing one does not need a restart, and a language whose toolchain is
absent appears in the picker as unavailable *with the reason* rather than silently missing.
Override the executables with `CPINTEL_RUNNER_CPP` and `CPINTEL_RUNNER_PYTHON`.

Python gets a build step even though it does not need one: `py_compile` parses the file before
anything runs. Without it, a syntax error would surface as the same runtime error on all twelve
of a problem's samples, with the real message buried in twelve copies of one traceback; with it,
it is reported once, with the line and the caret, exactly as a `g++` error is. A program that
parses and then raises still fails per test, as it should.

The interpreter runs in isolated mode (`-I`), so `PYTHONPATH`, the user site-packages directory
and `PYTHON*` environment variables are ignored — which matters on the documented fallback path,
where bubblewrap is unavailable and `~/.local/lib` would otherwise be readable. Memory is capped
the same way C++ is: CPython starts fine under it, and without it a one-line Python program will
quietly take a gigabyte of the host.

Adding Java or SQL means implementing `LanguageRuntime` — one class, no other changes; see the
notes on that interface for how SQL differs from the others.

Note that the Docker image ships **neither** toolchain, which is deliberate: the `prod` profile
turns the runner off, and a container that could compile submitted code would be handing every
account a shell. Installing them there is only worth doing alongside a sandbox you trust.

### Restricting an examination to certain languages

An examination can be set to accept only some languages — see
[Running an examination](#running-an-examination). The restriction holds in three places from
one setting: the editor's submit picker, the server's submit endpoint, and the Run button, which
refuses a language the paper does not take rather than letting somebody rehearse in it for an
hour. Hiding the option alone would stop an honest mistake and nothing else, since the submit
endpoint is a plain authenticated POST.

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
