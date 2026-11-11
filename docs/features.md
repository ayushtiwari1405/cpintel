# Features

What CPIntel does, area by area. For running each part, follow the links to the topic guides in [the documentation table](../README.md#documentation).

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
token revocation), and the external judges (Codeforces / DOMjudge).

See [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) for the system diagram, the ER diagram and
the reasoning behind the harder decisions, and [`docs/SRS.md`](SRS.md) for requirements.

## Stack

| Layer | Technology |
|---|---|
| Frontend | React 19, TypeScript, Vite 8, TailwindCSS, Recharts, Zustand, TanStack Query; light and dark themes |
| Backend | Spring Boot 3, Java 21, Spring Security, JWT, Spring Data JPA |
| Primary DB | PostgreSQL 16 — relational system of record; scoring runs in the Java service layer |
| Document DB | MongoDB — raw submission history, personal files, contest snapshots |
| Cache | Redis — sessions, JWT blacklist, per-user token revocation, analytics cache |
| Integrations | Codeforces REST + scraping, DOMjudge REST v4 |
| Migrations | Flyway — 33 versioned migrations (schema, indexes, materialized views, audit indexes, groups, super admin role, scheduler locks, node-level mastery, examinations, examination access, placement results, Codeforces-only cleanup, examination-mode sessions, several events per judge contest, examination leaderboards, classrooms, audit log by classroom, DOMjudge logins in PostgreSQL, marking an event done, rejudging a problem, TA marking, freezing TA marks, refresh-token digests and families, skill ratings, one sync at a time, examination grants kept in PostgreSQL, desktop-app-only examinations, which admin made each account, the classroom question bank, uploaded statement PDFs, model-solution checks, publishing examinations to the judge, classroom Codeforces keys and TA question permission) |
| Containerization | Docker Compose — 11 services: 3 behind the `monitoring` profile and certbot behind `letsencrypt`. `docker-compose.prod.yml` swaps the builds for CI images |
| Editor | Monaco, bundled locally (no CDN) — shared by the Practice, Compete and examination workspaces |
| Code runner | g++, gcc and CPython under bubblewrap + rlimits, in its own locked-down container on a server; judged against the statement's sample tests |
| Desktop | Electron wrapper that loads the deployed server, plus examination monitoring and Codeforces access through its own browser session |
| Monitoring | Prometheus, Grafana, Loki (optional profile) |

## Analytics and planning

- Link a Codeforces account; async background sync of its full submission and contest history.
  (LeetCode and CodeChef were supported once and dropped: their APIs gave only a slice of a
  user's history, so analytics built on them misdescribed people.)
- Skill ratings in Codeforces points per topic and per skill-tree node, with an uncertainty:
  the person's rating moved by how they did on each topic's problems in their rated rounds, or
  their placement-gauntlet areas if they choose (for anyone whose Codeforces rating does not
  reflect them). Without either, practice coverage is shown instead of a number. Measured on
  real histories in `scripts/analytics-eval` — see there for what it does and does not predict
- Contest analytics: rating history, wrong-submission patterns, behavioural insight generation
- Unified rating: the Codeforces rating mapped onto a 0-1000 scale
- A practice plan (`/recommendations`): a daily sheet, a weekly plan in sessions with a goal,
  an SM-2-style revision queue that offers old solves to redo, and preparation for the next rated
  Codeforces round. Each skill on it says why it is there; where the user's own history is too
  thin to rank, the plan takes the roadmap's next skills, so the two do not disagree
- 140-node adaptive roadmap with real prerequisite chains, live-pulled Codeforces problems per
  node, and auto-unlock based on measured mastery
- A placement gauntlet (`/roadmap/gauntlet`): a short tiered test per area whose result moves
  roadmap nodes forward — never back — so a newcomer does not start at the bottom of a tree they
  have already climbed. It can be retaken every `CPINTEL_GAUNTLET_RETAKE_DAYS` days
- Dashboards: topic radar, mastery heatmap, rating-over-time, recent contests

## Practising and competing

Practice is open at any time. **Compete** is for contests anyone may open — pick Codeforces or
DOMjudge from two tiles. **Exams** has its own sidebar entry and page, and lists only the
examinations assigned to you.

- A split-pane workspace on Practice, Compete and examinations alike — statement and problem
  list on the left, editor above a testcase console on the right, with draggable dividers whose
  positions are remembered
- Sample tests are seeded as **editable** cases; add your own to run the program on anything,
  and Run executes them all
- A Run button that compiles your solution locally and checks it against the samples before you
  spend a real submission — see [Running code](code-runner.md#running-code)
- **A code visualiser** (`/visualise`, sidebar **Visualiser**): step a C, C++ or Python program
  forwards and back and watch the stack, heap and pointers, data structures (arrays with their
  index variables, DP grids, graphs, trees, linked lists) and the recursion tree; with notes,
  breakpoints, a presentation mode and share links for teaching. All in the browser — see
  [Code visualiser](visualiser.md)
- Submit straight to the judge from the editor, with the verdict polled back into the console —
  Codeforces and DOMjudge both, from the same page
- **DOMjudge contestants never type a judge password.** An admin attaches each person's
  DOMjudge login once, from the group's member table; the contestant then signs into CPIntel as
  usual and the arena competes as them, so the judge attributes every submission to their own
  team. They pick their round from a list of the contests DOMjudge says they are registered
  for, rather than hunting for a contest id. Statements are the problem package's PDF,
  embedded, with its sample data seeded into the console
- **A leaderboard in the sidebar**, on DOMjudge contests: the same board an examination has
  (see below), ranked from CPIntel's own submission archive rather than read from the judge's
  scoreboard, so it shows everyone whatever the judge makes public. A contest set up as a
  CPIntel event uses that event's window, marks and leaderboard settings; one opened straight
  from the judge ranks whoever submitted through CPIntel inside the judge's own window, one
  mark per problem, no penalty, recomputed at most once a minute
- Submitting to Codeforces uses a session you create yourself in your own browser — the desktop
  app signs you in inside a window it owns, the web build uses a local helper. Nothing anywhere
  asks for your Codeforces password; see [Connecting Codeforces](codeforces.md#connecting-codeforces)
- Submission history with the failing test attached, and an archive of everything you sent
- Personal files: upload your own templates, snippet headers and notebook PDFs, and open them
  from inside a running contest without leaving the page

## Teams and team contests

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

## Examinations

An examination is a DOMjudge contest with CPIntel's invigilation around it. It differs from an
ordinary contest in four ways, and nothing else:

- **It is assigned, never public.** Teams, named individuals, or both. Somebody named twice
  counts once, and a candidate sees only the examinations that are theirs
- **It has a life of its own**: `DRAFT → SCHEDULED → ACTIVE → ENDED → ARCHIVED`. A draft is
  invisible to candidates however close its start time is; the three middle states follow the
  clock, so nobody has to remember to press anything for a paper to open
- **It is sat in examination mode, entered with a password handed out for that paper.** The
  login page takes two kinds of password. A candidate's own password opens **normal mode** —
  practice, analytics, their past examinations. Their **examination sign-in password**, issued
  per candidate for one paper and printed on the slip on their desk, opens **examination
  mode**: a session that can reach that one paper and nothing else — no practice, no profile,
  no files, no other contest — and that expires fifteen minutes after the paper closes. New
  passwords are issued for every examination, and none is ever emailed. It works from an hour
  before the start until the end. The two modes cannot reach into each other: a normal session
  cannot open, unlock or submit to a scheduled or running examination, and an examination
  session cannot touch anything outside it. Optionally the paper also has a **room password**.
  It is accepted before the start too, so candidates can enter, wait and connect Codeforces;
  until it is typed the candidate sees the clock and the rules and nothing else, and the problem
  list and statements appear only when the paper starts
- **It is monitored, and the candidate is told so on the screen where it happens.** Focus
  losses, returns, absences past the paper's own away threshold, problems opened, submissions
  and verdicts are all recorded. The threshold and the desktop restrictions are set per
  examination. Time in a Codeforces window CPIntel itself opened (signing in, or Codeforces'
  anti-bot check when submitting) is not counted as away; those windows stay on Codeforces'
  sign-in, check and submit pages and close themselves. **Only examinations are monitored** — an
  ordinary contest is practice among people who chose to enter it, and nothing watches one
- **The exam page keeps the code area clear.** The top bar has the paper's name, **Refresh**
  (reloads the paper's data without reloading the page, so full screen is kept), and *I have
  finished*; on a Codeforces paper it also says when the candidate is not signed in to
  Codeforces, with a **Sign in to Codeforces** button. The rules, notices and what is monitored
  sit in the sidebar, or above the paper when the sidebar is closed
- **It holds its candidates for its whole window.** From the start of the paper to its end,
  somebody assigned to it cannot sign in with their account password or renew a normal session,
  and any normal session they already held is ended (`EXAM_IN_PROGRESS`). The slip is the only
  way in; the rule lifts by itself when the paper ends. Admins are not held to it. Their own
  vault and code archive are closed too, so "personal files: off" holds everywhere and not
  only on the exam page
- **It keeps to its own window, not the judge's.** One DOMjudge contest can carry several
  events — a practice round one week, a paper the next — and each opens and closes on its own
  times. Submissions outside the event's window are refused even while the judge contest is
  still running, and only submissions made during the paper count as part of it
- **It has its own leaderboard**, ranked from CPIntel's own submission archive rather than
  the judge's board: most marks first — each problem is worth what the admin gave it — then
  anyone who submitted something ahead of anyone who submitted nothing, then less total time, with a solve timed from when CPIntel sent the accepted code — so a busy judge cannot reorder two people who submitted a
  second apart. An optional per-wrong-attempt penalty is added (compilation errors do not
  count). The admin turns it on or off, sets how often it is recomputed and whether the final
  standings are released; after the end it keeps settling for ten minutes, then is final
- **It leaves a log.** Admins get a live monitoring dashboard while it runs — who is present,
  who is away and for how long, what each candidate was last seen doing — and a session log in
  two lists: **Students**, every assigned candidate with their counts, opening onto that
  person's own log; and **Suspicious activity**, the handful of rows worth a human look — long
  absences, frequent focus losses, signing in more than once (from different addresses ranks
  higher), a first submission within seconds of opening a problem, desktop restrictions firing.
  That list is worked out from the log on every read and never stored, so nothing is written
  against anyone's name. The log is kept for a configurable retention period
- **A candidate says when they are done.** *I have finished* asks for confirmation, records it
  and signs them out. A finished paper shows its questions and samples beside their code
- **Afterwards, candidates get their own code back** — in normal mode, under **Past
  examinations** on the Exams page. A paper that has ended opens onto the
  source of every submission that candidate made into it, read out of CPIntel's own archive
  rather than fetched from the judge. Their code, and nothing else: not their marks, not
  anybody else's verdicts, not the test data — results are published by whoever decides to
  publish them, and a review screen that quietly became a results screen would take that
  decision away from them. The one exception is the leaderboard's final standings, and only
  once the admin has released them

Everything on both screens is an observation rather than a finding. A focus loss is a focus
loss; whether it was a second screen, a notification or somebody answering the door is not
something software can know, which is why there is no single "suspicion score" anywhere in
the product.

## Administration

- JWT auth with refresh token rotation (the refresh token is an `HttpOnly`, `SameSite=Strict`
  cookie; the access token is kept in memory only) and Argon2id password hashing
- Three roles — `USER`, `ADMIN`, `SUPER_ADMIN` — with role assignment, the creation of other
  admins, and account deletion reserved to the super admin, so the tier that hands out
  privilege sits above the tier that uses it. An ordinary admin runs the room but cannot
  deactivate, edit or re-password another administrator. Self-registration is closed by
  default; see [Roles and accounts](accounts.md#roles-and-accounts)
- Sign in with a username or an email address; set your own password from your profile, or
  reset it through a one-use emailed link. The first password is handed over with the username
  and never mailed — see [Passwords](accounts.md#passwords)
- Admin console at `/admin`: deployment overview, account administration (roles, profile
  corrections, deactivation, password resets, signing an account out everywhere, participation
  history), an append-only audit trail of every sign-in and administrative change, team
  management (including moving somebody between teams in one step), contests and examinations
  with their rosters, problems, passwords, monitoring and logs, and per-contest control of
  personal file access


## Where things are in the app

| Route | Who | What |
|---|---|---|
| `/dashboard` | anyone | Topic radar, streak, recent contests |
| `/analytics` | anyone | Mastery heatmap, rating history, behavioural insights |
| `/recommendations` | anyone | Practice plan: today, this week, revision, contest prep |
| `/roadmap` | anyone | The 140-node dependency graph with problems per node |
| `/roadmap/gauntlet` | anyone | The placement gauntlet |
| `/practice` | anyone | Problem search, statement, editor, local runner, submit |
| `/compete` | anyone | Contests on Codeforces or DOMjudge: statements, editor, submissions, rank |
| `/exams` | anyone | Your assigned examinations, and past ones to read back |
| `/exam` | exam mode | The one paper an examination session can reach; every other route redirects here |
| `/platforms` | anyone | Link and sync Codeforces; connect the Codeforces session used for submitting |
| `/teams` | anyone | Team contests you are in, and where you placed |
| `/profile` | anyone | Your account |
| `/admin` | admin | Deployment overview and recent activity |
| `/admin/users` | admin | Accounts, roles, deactivation, session revocation |
| `/admin/audit` | admin | The append-only audit trail |
| `/admin/teams` | admin | Teams, members, DOMjudge logins, contests, standings, team analytics |
| `/admin/exams` | admin | Contests and examinations: rosters, problems, lifecycle |
| `/admin/exams/{id}` | admin | One event — settings, who sits it, problems, passwords, live monitoring, session log, leaderboard |
| `/admin/contest-files` | admin | Which contests may offer personal files |
