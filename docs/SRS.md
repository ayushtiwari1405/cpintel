# Software Requirements Specification - CPIntel

## 1. Introduction

### 1.1 Purpose
CPIntel is a competitive programming intelligence platform. It aggregates a user's activity from Codeforces, LeetCode and CodeChef and produces analytics, mastery scoring and adaptive practice recommendations that no single platform provides on its own. It also provides the working surface for a practice or contest session — statement, editor, local test runs, submission — and lets an administrator run group contests over an external judge.

### 1.2 Scope
In scope: account linking and sync; topic mastery computation; contest analytics; unified cross-platform rating; recommendation generation; spaced-repetition revision scheduling; an adaptive skill roadmap; an in-app editor with local sample-test execution; forwarding submissions to Codeforces; live contest participation with statements, submissions and rank; per-user file storage reachable during a contest; administrator tooling for accounts, audit and group contests; and contest monitoring that reports absence from the contest window.

Out of scope: **being an online judge.** CPIntel never decides a verdict. It compiles and runs code against sample tests as a rehearsal harness, and it forwards submissions to Codeforces or reads DOMjudge's scoreboard, but the judging itself always belongs to the external platform. Also out of scope: hosting contests of its own, authoring problems, and any form of proctoring beyond what a normal desktop application can observe about its own window.

An earlier revision listed code execution, direct submission and live contest tracking as out of scope. All three were subsequently built; this section has been corrected rather than left to disagree with the product.

### 1.3 Definitions
- **Mastery score** - 0-100 score per DSA topic derived from accuracy, solve volume and recency.
- **Decay score** - portion of mastery score considered "faded" due to inactivity, computed via exponential decay.
- **Unified score** - single cross-platform rating combining normalized Codeforces, LeetCode and CodeChef ratings.
- **Roadmap node** - one DSA sub-skill (e.g. "DFS & BFS") in the 35-node dependency tree.
- **Group** - a named set of CPIntel users an administrator measures against each other.
- **Group contest** - an external contest, on Codeforces or DOMjudge, that a group sits together and is ranked within.
- **Monitoring** - observation of whether the contestant's window has focus during a group contest. Not proctoring: it prevents nothing and can see nothing outside its own window.

## 2. Overall description

### 2.1 User classes
- **USER** - standard account, full feature access scoped to their own data. Sees group contests they are enrolled in and their own placing.
- **ADMIN** - full account administration, the audit trail, group and contest management, and the contest-file policy. There is no sign-up path to this role; see FR-8.1.

### 2.2 Operating environment
Backend: Java 21 / Spring Boot 3, deployed via Docker. Frontend: React 19 SPA served via Nginx or the Vite dev server, or wrapped in Electron for desktop. Databases: PostgreSQL 16 (relational system of record), MongoDB (document), Redis (cache and revocation).

The desktop build is the same SPA plus a main process that can observe the window from outside the page. Every feature works in the browser; contest monitoring is weaker there and reports itself as such.

## 3. Functional requirements

### FR-1 Authentication
- FR-1.1 Users register with email, username, password (Argon2id hashed).
- FR-1.2 JWT access tokens (15 min) with rotating refresh tokens (7 day) stored server-side and revocable.
- FR-1.3 Logout blacklists the active access token in Redis until natural expiry.
- FR-1.4 Refresh re-checks that the account is still active, so an account deactivated mid-session cannot renew itself.
- FR-1.5 An administrator can retire every access token a user currently holds, so a role change or deactivation takes effect within seconds rather than at token expiry.

### FR-2 Platform integration
- FR-2.1 Users link one account per platform (Codeforces, LeetCode, CodeChef).
- FR-2.2 Handle existence is validated against the live platform before linking.
- FR-2.3 Initial link triggers a full async sync of submission history into MongoDB.
- FR-2.4 Subsequent syncs are incremental, run on demand or nightly via scheduler.
- FR-2.5 CodeChef has no public JSON API; profile data is obtained via HTML scraping (Jsoup) since this is the only available method.

### FR-3 Topic mastery
- FR-3.1 Mongo-stored submissions are normalized into PostgreSQL `topic_mastery` rows per canonical topic (14 topics: Arrays, Strings, Binary Search, Two Pointers, Greedy, DP, Graphs, Trees, Segment Trees, Binary Lifting, Number Theory, Bit Manipulation, Tries, Geometry).
- FR-3.2 Mastery score is computed by `ScoringFormulas.mastery` from a weighted blend of accuracy (40%), solve volume on a log scale saturating near 1500 solves (35%) and recency (25%).
- FR-3.3 Decay score uses exponential half-life decay (30-day half-life) applied to mastery based on days since last practice in that topic.

### FR-4 Contest analytics
- FR-4.1 Codeforces rating history and per-contest submission stats (wrong-submission count, first-solve time) are pulled and stored as `contest_summaries`.
- FR-4.2 `ContestAnalysisService` behavioral pattern detection flags users who accumulate penalties disproportionately late in contests.
- FR-4.3 Consistency score is derived from the standard deviation of rating changes across contests.

### FR-5 Unified rating
- FR-5.1 Each platform rating is normalized to a 0-1000 scale using platform-specific min/max bounds.
- FR-5.2 Final unified score is a configurable weighted average (default CF 40%, LC 35%, CC 25%) across only the platforms the user has linked.

### FR-6 Recommendations
- FR-6.1 Daily sheet: top 3 weakest topics with a target difficulty band.
- FR-6.2 Weekly plan: 7 topics prioritized by combined mastery gap and decay.
- FR-6.3 Revision queue: SM-2-inspired spaced repetition; completing a revision increases the interval and ease factor and reduces decay score.

### FR-7 Adaptive roadmap
- FR-7.1 35 DSA sub-skill nodes (e.g. "Two Pointers", "Segment Trees with Lazy Propagation") form a directed prerequisite graph.
- FR-7.2 A node unlocks when all prerequisite nodes' parent-topic mastery reaches >=35%; it is marked complete at >=75% mastery.
- FR-7.3 Each unlocked node displays up to 8 live Codeforces problems filtered by the node's tag set and difficulty band, pulled from the cached CF problemset (`problemset.problems`, 6-hour in-memory TTL) and cross-referenced against the user's solved-problem set.

### FR-8 Administration
- FR-8.1 There is no registration path to ADMIN and no seeded administrator account. An existing account is promoted at startup by naming its email address in configuration; nothing is created, so no default administrator password exists anywhere in the deployment.
- FR-8.2 Administrators may change roles, deactivate and reactivate accounts, and sign an account out of every device.
- FR-8.3 The system refuses any change that would leave nobody able to administer it: an admin cannot change their own role or deactivate their own account, and the last active admin cannot be demoted or deactivated by anyone.
- FR-8.4 Accounts are never deleted through the console. Deactivation stops sign-in and preserves the record of what the account did.
- FR-8.5 Every sign-in, failed sign-in, registration and administrative change is recorded in an append-only audit trail with the originating address. Failed sign-ins are recorded against the address attempted, since the case worth spotting has no account to attach to.
- FR-8.6 The audit trail has no edit or delete path anywhere in the product.

### FR-9 Practice and contest workspace
- FR-9.1 Problems may be searched by id, name, rating band and tag; statements are rendered with mathematics typeset.
- FR-9.2 An in-app Monaco editor keeps a separate buffer per problem, so switching problems never loses work.
- FR-9.3 Solutions may be compiled and run on the backend host against editable test cases seeded from the statement's samples. This is a rehearsal harness and reports itself as such; it is not a verdict.
- FR-9.4 Execution is bounded by wall-clock, CPU, address-space and output-size limits, and sandboxed with bubblewrap where available. `GET /api/run/status` reports whether isolation is actually in effect, and the UI states it when it is not.
- FR-9.5 Code execution is disabled by default in the `prod` profile, because on a shared deployment it would hand every account a shell.
- FR-9.6 Solutions may be submitted to Codeforces from the editor using the user's own stored session, with the verdict polled back. Stored session cookies are encrypted at rest.
- FR-9.7 During a live contest the workspace shows the statements, the problem list with per-problem state, the user's submissions and their rank.

### FR-10 Personal files
- FR-10.1 Users may upload their own templates, notes and reference material, subject to per-file, per-vault and file-count limits.
- FR-10.2 Files are reachable from inside a running contest without leaving the page; text is previewed inline and images and PDFs are rendered.
- FR-10.3 Whether a contest offers personal files is an administrator decision, resolved as: a rule for that contest, then a deployment-wide rule, then configuration. It ships enabled, so rules are written only as exceptions.
- FR-10.4 Every lookup is scoped by owner, so another user's file id reads as not-found rather than as forbidden.
- FR-10.5 The served content type is derived from the file extension, never from what the uploading client declared.

### FR-11 Group contests
- FR-11.1 An administrator may create a group, add CPIntel users to it, and attach contests running on Codeforces or DOMjudge.
- FR-11.2 Group members are matched to the external board by their linked Codeforces handle, or by a team name recorded on the membership where no such link exists (always the case for DOMjudge).
- FR-11.3 The group's ranking is computed over its members only: more problems first, then fewer penalty minutes, with equal results sharing a rank.
- FR-11.4 A member the judge has no row for is left unranked rather than ranked last, and is named as unmatched, because a missing row nearly always means a handle mismatch rather than a bad result.
- FR-11.5 Codeforces standings are computed from each member's submission list, because Codeforces refuses filtered standings queries for public contests. Every screen that shows those numbers states that they are computed and may differ from a points-scored round's official order.
- FR-11.6 DOMjudge standings are read from the judge's own scoreboard and agree with it.
- FR-11.7 Standings are a cached snapshot, refreshed on a schedule while a contest is live and on demand, and every view shows when it was taken and whether the last refresh failed.
- FR-11.8 Participants see their own placing. Publishing the full board to members is not automatic.

### FR-12 Contest monitoring
- FR-12.1 While a group contest is live, the client observes whether the contest window has focus. The desktop build measures this in the Electron main process; the browser build uses the Page Visibility API and window focus.
- FR-12.2 A contestant away from the contest window for longer than ten seconds is warned — a native notification on the desktop, an alternating tab title in the browser — repeated while they remain away.
- FR-12.3 Absences past that threshold are reported to the administrator running the group. Briefer absences are counted locally and never escalated.
- FR-12.4 Contestants are told they are being monitored, and told the threshold, on the page where the contest is sat. Monitoring is never silent.
- FR-12.5 Reports are accepted only from the account they concern, only for a contest that account is a member of, and only for timestamps inside the contest window. Each event carries a client-generated identifier under a unique constraint, so retries cannot inflate a count.
- FR-12.6 The system does not attempt to prevent switching away. It does not capture keyboard shortcuts, and it does not put the window into kiosk mode; an earlier revision did both, which disrupted ordinary use of the computer and could not take the window-switching keys from Windows or macOS in any case.
- FR-12.7 Reported observations are presented as observations. The product does not compute a single suspicion score, and states in the interface that a clean record is not evidence of anything, since a second device is invisible to it.

## 4. Non-functional requirements

- **NFR-1 Performance** - the CF problemset cache avoids re-fetching ~10k problems per request; analytics queries are cached in Redis with topic-appropriate TTLs (6-24h); group standings are snapshotted rather than computed on read, because building one costs a rate-limited API call per member.
- **NFR-2 Resilience** - external platform clients retry with exponential backoff (2-3 attempts) and degrade gracefully. A failed standings refresh keeps the previous snapshot and records the reason beside it rather than showing stale numbers as current. An unreachable file store or rule store falls back to the configured default rather than failing the contest page.
- **NFR-3 Security** - stateless JWT auth, CORS restricted per environment, Argon2id password hashing, parameterized queries throughout. `/api/admin/**` is gated in the security filter chain as well as per controller, so a new admin controller cannot ship authenticated-but-open by omitting an annotation.
- **NFR-4 Portability** - the full stack runs via Docker Compose on any OS; the SPA is shared unmodified between the web build and the Electron desktop build.
- **NFR-5 Recoverability** - monitoring cannot trap the machine: a crashed or unresponsive renderer releases it automatically, as does application quit.

## 5. Data model summary

Relational (PostgreSQL): `users`, `platform_accounts`, `topic_mastery`, `contest_summaries`, `recommendations`, `revision_schedule`, `roadmap_nodes`, `unified_scores`, `refresh_tokens`, `verification_tokens`, `sync_jobs`, `audit_log`, `contest_groups`, `group_members`, `group_contests`, `group_standings`, `contest_violations`.

Document (MongoDB): `cf_submissions`, `lc_submissions`, `cc_submissions`, `code_submissions`, `contest_snapshots`, `activity_feed`, `personal_files`, `contest_file_rules`.

Full ER diagram: [`docs/ARCHITECTURE.md`](ARCHITECTURE.md#entity-relationship-diagram).

## 6. External interfaces

| Platform | Method | Notes |
|---|---|---|
| Codeforces | REST (`/api/user.info`, `/user.status`, `/user.rating`, `/problemset.problems`, `/contest.status`) plus authenticated HTML scraping for statements, submission and standings | Official API is stable and rate-limited client-side (500ms). `contest.standings` refuses filtered queries for public contests, which is why group standings are computed per handle |
| LeetCode | Unofficial GraphQL endpoint | No official public API; schema can change without notice |
| CodeChef | HTML scraping (Jsoup) | No public JSON API exists for user profiles |
| DOMjudge | REST v4 (`/contests`, `/contests/{id}/teams`, `/contests/{id}/scoreboard`) | Self-hosted, so unconfigured by default; basic auth, omitted when the scoreboard is public. The whole board arrives in one request |
