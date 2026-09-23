# Software Requirements Specification - CPIntel

## 1. Introduction

### 1.1 Purpose
CPIntel is a competitive programming management and intelligence platform. It aggregates a user's activity from Codeforces, LeetCode and CodeChef and produces analytics, mastery scoring and adaptive practice recommendations that no single platform provides on its own. It provides the working surface for a practice, contest or examination session — statement, editor, local test runs, submission — and lets an administrator run team contests and invigilated examinations over an external judge.

### 1.2 Scope
In scope: account linking and sync; topic mastery computation; contest analytics; unified cross-platform rating; recommendation generation; spaced-repetition revision scheduling; an adaptive skill roadmap; an in-app editor with local sample-test execution; forwarding submissions to Codeforces; live contest and examination participation with statements, submissions and rank; per-user file storage reachable during a contest; administrator tooling for accounts, audit, teams, contests and examinations; examination assignment, lifecycle, monitoring and session logging; and contest monitoring that reports absence from the contest window.

Out of scope: **being an online judge.** CPIntel never decides a verdict. It compiles and runs code against sample tests as a rehearsal harness, and it forwards submissions to Codeforces or reads DOMjudge's scoreboard, but the judging itself always belongs to the external platform. Also out of scope: hosting contests of its own, authoring problems, and any form of proctoring beyond what a normal desktop application can observe about its own window.

An earlier revision listed code execution, direct submission and live contest tracking as out of scope. All three were subsequently built; this section has been corrected rather than left to disagree with the product.

### 1.3 Definitions
- **Mastery score** - 0-100 score per DSA topic derived from accuracy, solve volume and recency.
- **Decay score** - portion of mastery score considered "faded" due to inactivity, computed via exponential decay.
- **Unified score** - single cross-platform rating combining normalized Codeforces, LeetCode and CodeChef ratings.
- **Roadmap node** - one DSA sub-skill (e.g. "DFS & BFS") in the 35-node dependency tree.
- **Team** - a named set of CPIntel users an administrator measures against each other. Stored as a contest group; "team" is the name used throughout the product.
- **Event** - a contest or an examination. One record, with the rules that differ stated per kind.
- **Examination** - an assigned, monitored, logged event run on DOMjudge. Never public.
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
- FR-1.1 Accounts carry an email address, a username and a password (Argon2id hashed). Self-registration is closed by default; an account is created by an administrator, and its first password is handed over with the username rather than emailed, because a mailed first password is a live credential sitting in a mailbox and because a closed deployment may have no mail transport at the moment accounts are made.
- FR-1.1a Sign-in accepts either the email address or the username, in one field, matched case-insensitively. Neither form is treated as more authoritative, and a refusal does not indicate which of the identifier or the password was wrong.
- FR-1.2 JWT access tokens (15 min) with rotating refresh tokens (7 day) stored server-side and revocable.
- FR-1.3 Logout blacklists the active access token in Redis until natural expiry.
- FR-1.4 Refresh re-checks that the account is still active, so an account deactivated mid-session cannot renew itself.
- FR-1.5 An administrator can retire every access token a user currently holds, so a role change or deactivation takes effect within seconds rather than at token expiry.
- FR-1.6 A signed-in user may change their own password, which requires the current one — a valid session may be an unlocked machine, and that check is what makes the person at the keyboard the owner.
- FR-1.7 A user who cannot sign in may request a reset link sent to the address on their account. The link is stored only as a SHA-256 digest, is valid once, expires after a configurable interval, and is retired when a new one is issued or the password changes by any route.
- FR-1.8 Setting a password by any route revokes every refresh token and access token the account holds, and notifies the address on the account. Somebody resetting a password they believe is known to another party has not finished until that party is signed out, and somebody whose account was taken over finds out in seconds rather than at their next sign-in.
- FR-1.9 The reset request endpoint answers identically whether or not the address belongs to an account. It is reachable without authentication, and an answer that distinguished the two would let a caller test a list of addresses against the roster of whoever is being examined.
- FR-1.10 With no mail transport configured, the system still operates: the message and its link are written to the application log so an operator can complete a reset by hand, and startup warns that delivery is off. A deployment on a closed network is a supported arrangement rather than a misconfiguration.

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
- FR-8.2 Administrators may deactivate and reactivate accounts, correct account details, set a new password, and sign an account out of every device. Changing roles remains the super administrator's.
- FR-8.2a An administrator may not act on another account holding ADMIN or SUPER_ADMIN — editing, deactivating, signing out and setting a password are all refused unless the caller is a super administrator. An administrator who can deactivate or re-password a peer can remove the person who would have stopped them, which is the escalation FR-8.2 reserves role assignment for, reached from a different screen. A super administrator has no such limit over any account in the deployment.
- FR-8.2b A password set by an administrator is returned once and never emailed; it is stored hashed, and the owner is told by mail that an administrator did it, with no password and no link in the message. The account is recorded as not having a password its owner chose, which the console displays.
- FR-8.3 The system refuses any change that would leave nobody able to administer it: an admin cannot change their own role or deactivate their own account, and the last active admin cannot be demoted or deactivated by anyone.
- FR-8.4 Deleting an account is the super administrator's alone, and is refused for anybody who has sat an examination, because the cascade would take their session log — evidence about a paper somebody may still be marking or appealing — with it. Deactivation stops sign-in, preserves the record of what the account did, and is what "remove this person" ordinarily means.
- FR-8.5 Every sign-in, failed sign-in, registration and administrative change is recorded in an append-only audit trail with the originating address. Failed sign-ins are recorded against the address attempted, since the case worth spotting has no account to attach to.
- FR-8.6 The audit trail has no edit or delete path anywhere in the product.

### FR-9 Practice, contest and examination workspace
- FR-9.1 Problems may be searched by id, name, rating band and tag; statements are rendered with mathematics typeset — as HTML from Codeforces, as the problem package's PDF from DOMjudge.
- FR-9.2 An in-app Monaco editor keeps a separate buffer per problem, so switching problems never loses work.
- FR-9.3 Solutions may be compiled and run on the backend host against editable test cases seeded from the statement's samples. This is a rehearsal harness and reports itself as such; it is not a verdict.
- FR-9.3a The runner supports C++ and Python 3. Each toolchain is probed per request rather than at startup, so one installed on a running host becomes available without a restart, and a language whose toolchain is absent is offered as unavailable with the reason rather than omitted — a contestant who cannot find a language is told why.
- FR-9.3b An interpreted language is still parsed before it is run, so that a syntax error is reported once as a build failure rather than as an identical runtime error on every test case.
- FR-9.3c Every language is held to the same wall-clock, CPU, address-space, output-size and process-count limits. A language exempted from the address-space limit would be able to exhaust the host's memory, which the others cannot.
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

### FR-11 Teams and team contests
- FR-11.1 An administrator may create a team, add CPIntel users to it, and attach contests running on Codeforces or DOMjudge.
- FR-11.2 Team members are matched to the external board by their linked Codeforces handle, or by a team name recorded on the membership where no such link exists (always the case for DOMjudge).
- FR-11.3 The group's ranking is computed over its members only: more problems first, then fewer penalty minutes, with equal results sharing a rank.
- FR-11.4 A member the judge has no row for is left unranked rather than ranked last, and is named as unmatched, because a missing row nearly always means a handle mismatch rather than a bad result.
- FR-11.5 Codeforces standings are computed from each member's submission list, because Codeforces refuses filtered standings queries for public contests. Every screen that shows those numbers states that they are computed and may differ from a points-scored round's official order.
- FR-11.6 DOMjudge standings are read from the judge's own scoreboard and agree with it.
- FR-11.7 Standings are a cached snapshot, refreshed on a schedule while a contest is live and on demand, and every view shows when it was taken and whether the last refresh failed.
- FR-11.8 Participants see their own placing. Publishing the full board to members is not automatic.
- FR-11.9 An administrator may move somebody between teams in one action; their results stay with the events they were computed for rather than following the transfer.
- FR-11.10 Per-team analytics report participation rate, average problems solved, average score and the team's recent events.

### FR-11A Examinations
- FR-11A.1 An examination is created as a draft and is invisible to candidates until it is published, however close its start time is.
- FR-11A.2 An examination is assigned to teams, to named individuals, or to both. Somebody reached by more than one assignment is one participant.
- FR-11A.3 An examination is never public, and runs on the DOMjudge instance the deployment controls.
- FR-11A.4 The lifecycle is `DRAFT → SCHEDULED → ACTIVE → ENDED → ARCHIVED`. Draft and archived are stored; scheduled, active and ended follow the window, so nothing has to be pressed for a paper to open or close. Ending early moves the window rather than setting a flag.
- FR-11A.5 An administrator sets an examination's problems, their order and what each is worth. Statements, test data and verdicts remain the judge's.
- FR-11A.5a An administrator may restrict an examination to a set of programming languages. The restriction is expressed in CPIntel's own language names and mapped onto each judge's vocabulary, so one decision holds across judges that name languages differently and across the local runner, which names them differently again.
- FR-11A.5b The restriction is enforced wherever a candidate meets a language: the editor offers only the permitted ones, the submission endpoint refuses the others, and the local runner refuses to run them. Enforcement at the picker alone would stop an honest mistake and nothing more, since the submission endpoint is an ordinary authenticated request.
- FR-11A.5c The restriction narrows what the judge offers rather than replacing it. A language the judge does not offer for that contest remains unavailable whether or not it is permitted here.
- FR-11A.5d A judge language the system cannot recognise is treated as not permitted while a restriction is in force. An administrator who named a set meant that set, and admitting an unrecognised language would overrule them silently; the condition is recoverable by removing the restriction.
- FR-11A.5e A restriction naming no language the system recognises is treated as no restriction. The alternative reading — that nothing is permitted — would exclude every candidate from a sitting because of a stored value none of them can see or correct.
- FR-11A.5f Language names are validated when an examination is saved, so a name the system does not recognise is refused at that point rather than silently narrowing the examination when it is sat.
- FR-11A.5g An examination with no restriction set accepts whatever the judge accepts. This is the default.
- FR-11A.6 An administrator sets the away-time threshold and the desktop restrictions per examination.
- FR-11A.7 A candidate sees only the examinations assigned to them; an examination that exists but is not theirs is answered as not found rather than as forbidden.
- FR-11A.7a An examination may carry two passwords, both generated by an administrator: one for the whole paper, and one per candidate. Being assigned an examination is not sufficient to enter it — the assignment was made in advance and cannot establish that a candidate is in the room, and the window establishes that the paper is open but not that it is open for a particular person. The shared password establishes that a sitting has begun; the per-candidate code establishes which candidate is present, which the shared password cannot, since every candidate in the room holds it.
- FR-11A.7b Neither password is sent by email, and no interface offers to send one. They are distributed physically by the invigilator. A password delivered to the same mailbox that holds the account credential would provide no assurance beyond the credential itself.
- FR-11A.7c Both passwords are stored encrypted under a key held in the environment rather than hashed, because the invigilator must read them back to print candidate slips before the sitting and to reissue a lost code during it. An unreadable stored value — the result of a key rotation — is treated as no match rather than as an empty one.
- FR-11A.7d An examination may be unlocked only while its window is open. A password obtained in advance does not grant access before the sitting begins.
- FR-11A.7e A successful unlock grants access for the remainder of the window and records the password generation under which it was issued. Regenerating the shared password invalidates every access granted under the previous one. An administrator may also revoke one candidate's access individually, and reissue one candidate's code without affecting any other.
- FR-11A.7f Until an examination is unlocked, a candidate is given its window, its rules and the fact that a password is required, and is not given its problem list.
- FR-11A.7g A failed unlock does not indicate which password was incorrect, and is recorded in the audit trail and the session log.
- FR-11A.7h An examination for which no password has been generated does not require one. An administrator who has generated nothing has not elected to require anything, and an inferred requirement would exclude candidates from a sitting about to begin.
- FR-11A.7i Where no password key is configured, no examination password can be generated and the administrative interface states this, so the condition is discovered before a sitting rather than during one.
- FR-11A.8 Entering an examination is recorded, as are problems opened, submissions, focus losses, returns, absences past the threshold, restrictions triggered, exits and completion.
- FR-11A.9 An administrator has a live monitoring view of an examination in progress: who is present, who is away and for how long, focus-loss counts, last activity, submissions and current problem.
- FR-11A.10 An administrator may read the session log during and after an examination, filtered by candidate, team, event type and time.
- FR-11A.11 Session logs are kept for a configurable retention period and swept automatically. Deleting an examination that has been sat is refused; archiving is the way a finished one goes away.
- FR-11A.12 The examination interface notifies the candidate of the events that concern them: the paper about to start, started, time running low, focus lost, away past the threshold, and ended.
- FR-11A.13 After an examination has ended, a candidate may read back the source of every submission they made into it, from CPIntel's own archive rather than from the judge. This is limited to their own submissions and carries no marks, no other candidate's results and no test data: publication of results is a separate decision, and a review interface must not make it by default.
- FR-11A.14 That review is refused while the examination is running. During a sitting the editor holds the candidate's code, so there is nothing to recover, and a second view of the same submissions inside a monitored session serves no stated purpose.

### FR-12 Examination monitoring
- FR-12.0 Monitoring applies to examinations only. A team contest is not monitored, is not password-protected, and no submission into one is refused for want of a monitoring heartbeat. Monitoring is the property that distinguishes an examination from a contest, and applying it to rounds entered voluntarily would impose the cost of invigilation where no requirement calls for it.
- FR-12.1 While an examination is live, the client observes whether the examination window has focus. The desktop build measures this in the Electron main process; the browser build uses the Page Visibility API and window focus.
- FR-12.2 A candidate away from the examination window for longer than the event's threshold — ten seconds by default, set per examination — is warned, with a native notification on the desktop and an alternating tab title in the browser, repeated while they remain away.
- FR-12.3 Absences past that threshold are reported to the administrator running the examination. Briefer absences are counted locally and never escalated.
- FR-12.4 Candidates are told they are being monitored, and told the threshold, on the page where the examination is sat. Monitoring is never silent.
- FR-12.5 Reports are accepted only from the account they concern, only for an examination that account is assigned to, and only for timestamps inside the examination window. Each event carries a client-generated identifier under a unique constraint, so retries cannot inflate a count.
- FR-12.6 The system does not capture keyboard shortcuts; an earlier revision did, which disrupted ordinary use of the computer and could not take the window-switching keys from Windows or macOS in any case. What an examination may ask for is stated as policy and applied only as far as the operating system allows: holding the window in front, refusing navigation out of the examination, refusing other applications opened through the app, noticing an attempt to close it, and discarding clipboard content that arrived from outside. What could not be applied is reported rather than claimed.
- FR-12.7 Reported observations are presented as observations. The product does not compute a single suspicion score, and states in the interface that a clean record is not evidence of anything, since a second device is invisible to it.

## 4. Non-functional requirements

- **NFR-1 Performance** - the CF problemset cache avoids re-fetching ~10k problems per request; analytics queries are cached in Redis with topic-appropriate TTLs (6-24h); group standings are snapshotted rather than computed on read, because building one costs a rate-limited API call per member.
- **NFR-2 Resilience** - external platform clients retry with exponential backoff (2-3 attempts) and degrade gracefully. A failed standings refresh keeps the previous snapshot and records the reason beside it rather than showing stale numbers as current. An unreachable file store or rule store falls back to the configured default rather than failing the contest page.
- **NFR-3 Security** - stateless JWT auth, CORS restricted per environment, Argon2id password hashing, parameterized queries throughout. `/api/admin/**` is gated in the security filter chain as well as per controller, so a new admin controller cannot ship authenticated-but-open by omitting an annotation.
- **NFR-4 Portability** - the full stack runs via Docker Compose on any OS; the SPA is shared unmodified between the web build and the Electron desktop build.
- **NFR-5 Recoverability** - monitoring cannot trap the machine: a crashed or unresponsive renderer releases it automatically, as does application quit.

## 5. Data model summary

Relational (PostgreSQL): `users`, `platform_accounts`, `topic_mastery`, `contest_summaries`, `recommendations`, `revision_schedule`, `roadmap_nodes`, `unified_scores`, `refresh_tokens`, `verification_tokens`, `sync_jobs`, `audit_log`, `contest_groups`, `group_members`, `group_contests` (contests and examinations alike), `contest_assignments`, `contest_problems`, `group_standings`, `contest_violations`, `exam_events`.

Document (MongoDB): `cf_submissions`, `lc_submissions`, `cc_submissions`, `code_submissions`, `contest_snapshots`, `activity_feed`, `personal_files`, `contest_file_rules`.

Full ER diagram: [`docs/ARCHITECTURE.md`](ARCHITECTURE.md#entity-relationship-diagram).

## 6. External interfaces

| Platform | Method | Notes |
|---|---|---|
| Codeforces | REST (`/api/user.info`, `/user.status`, `/user.rating`, `/problemset.problems`, `/contest.status`) plus authenticated HTML scraping for statements, submission and standings | Official API is stable and rate-limited client-side (500ms). `contest.standings` refuses filtered queries for public contests, which is why group standings are computed per handle |
| LeetCode | Unofficial GraphQL endpoint | No official public API; schema can change without notice |
| CodeChef | HTML scraping (Jsoup) | No public JSON API exists for user profiles |
| DOMjudge | REST v4 (`/contests`, `/contests/{id}/teams`, `/contests/{id}/scoreboard`) | Self-hosted, so unconfigured by default; basic auth, omitted when the scoreboard is public. The whole board arrives in one request |
