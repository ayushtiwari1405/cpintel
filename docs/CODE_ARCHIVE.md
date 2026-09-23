# Code Archive — reading your own previous submissions without leaving the app

Every submission is stored as it is sent, and any submission Codeforces already knows about
can be pulled back on demand — source, and what the judge did with it test by test. The point
is a contest that locks the app down: it cannot also expect the user to open codeforces.com to
read code they already wrote.

Reached from the **History** button in the editor panel, on Practice, Compete and examinations
alike, and from a **See the test** link on the verdict badge.

---

## 1. The ordering that matters

`SubmissionArchive.recordAttempt` writes the source **before** it leaves the server, not after
the platform accepts it.

If the archive were written on success, every failure that loses the submission — dead session,
code Codeforces refuses, network gone — would also lose the code, in a UI where the user cannot
alt-tab to recover it. Writing first means the worst case is an archived attempt with no
submission id beside it, which is exactly the case where getting the code back is worth the
most. Those rows show as **"Never sent"** and still hold the source.

`recordAttempt` never throws. A submission must not fail because the archive is unavailable —
the user asked to submit, not to file a copy — so a broken Mongo degrades this feature and
nothing else.

The same write path is what a self-hosted judge needs. `CodeSubmission` is keyed by platform,
so a DOMjudge contest needs no new store and no fetch counterpart: we would own the data.

## 2. Two sources, one list

`attemptsForProblem` merges:

- **The archive** — everything CPIntel sent. Local read: instant, works offline.
- **Codeforces** — `contest.status` for the connected handle, covering submissions made before
  this feature existed, from another machine, or on the website. Source fetched on demand,
  then archived, so the second open is local.

Rows carry `stored`, rendered as a disk or cloud icon. With no session or an unreachable
Codeforces the call still returns the archived rows plus a notice — the offline path is the one
the lockdown scenario depends on, so it must not fail.

## 3. Getting source out of Codeforces — the part that cost a day

**The submission page cannot be scraped.** `/contest/{id}/submission/{id}` sits behind a
JavaScript fingerprint challenge ("Your browser is being checked"). Proven with the browser's
own complete cookie jar, on the session owner's own submission:

| URL | result |
|---|---|
| `/problemset/problem/4/A` | 61 KB, fine |
| `/submissions/{handle}` | 208 KB, fine |
| `/problemset/submit` | 97 KB, fine, logged in |
| `/contest/2218/submission/388933867` | **11 KB interstitial** |

No cookie gets past it. Headers make no difference (`Accept`, `Sec-Fetch-*`, `Referer`,
`Upgrade-Insecure-Requests` all tested). Ownership makes no difference. `#program-source-text`
is unreachable and always will be.

**`POST /data/submitSource` is not behind the challenge.** It is what the page's own script
calls, and it answers with everything. It needs a CSRF token — and that is the trap: taking the
token off the submission page means being blocked before reaching the endpoint. **The token is
per session, not per page**, so it comes from the homepage instead. `Referer` is mandatory;
Codeforces answers 403 without it.

Order in `CfWebSubmitClient.fetchSubmission`:

1. CSRF from `/` (cached 10 min per session — the History panel invites clicking through
   several submissions, and refetching a 200 KB page each time would be absurd).
2. `POST /data/submitSource`.
3. Rendered page, source only, as a fallback for the day the challenge is lifted.

### What the response carries

Per-test data is flattened into the top-level object as `input#1`, `answer#1`, `output#1`,
`verdict#1`, `checkerStdoutAndStderr#1`, `exitCode#1`, `timeConsumed#1`, `memoryConsumed#1`.
Numbers arrive as strings, everything is CRLF, and `compilationError` is the literal string
`"false"` when there was none.

`testsOf` walks the indices until one goes missing rather than trusting `testCount` — **during
a live round Codeforces reports the count and withholds the data behind it**, and an empty list
is the honest answer there. Test data is clipped at ~500 characters with a trailing ellipsis;
clipped rows are flagged so nobody debugs against half an input.

The failing test index comes from the API's `passedTestCount + 1`, never from parsing
`"Wrong answer on test 2"` out of the verdict markup — that is a rendered string for people.

## 4. Cookie filter: allowlist → denylist

`CfSessionStore.sanitiseCookieHeader` and the helper's `is_relevant` used an allowlist of names
we believed mattered (`JSESSIONID`, `X-User-Sha1`, `39ce7`, `RCPC`, …). Codeforces also sets a
`pow` cookie and a token under a **randomly generated hex name** (`70a7c28f3de`). Both were
being dropped from every session ever stored. A random name cannot be allowlisted.

Now: drop third-party analytics (`_ga*`, `_gid`, `_gat*`, `__utm*`, `_hj*`, `_clck`, `_clsk`),
keep everything else; require `JSESSIONID` for the header to count as a session. The host filter
already limits rows to codeforces.com, and a `Cookie` header carries no host information anyway,
so the allowlist never bounded what we held — only what worked.

**This was not the cause of the fetch failure** (see §3), but it was a real defect on its own.

## 5. Data model — `code_submissions` (Mongo)

Deliberately separate from `cf_submissions`, which is the analytics mirror: metadata only, safe
to wipe and re-derive. This collection holds what cannot be re-derived. Losing a row here loses
work; losing a row there costs one API call.

| field | note |
|---|---|
| `userId`, `platform` | `CODEFORCES` today, `DOMJUDGE` next |
| `externalId` | platform submission id; **null** while in flight and permanently null if refused |
| `contestId`, `problemIndex`, `problemName` | |
| `languageId`, `languageLabel` | id for resubmitting as-was; label filled in by the merge |
| `source`, `sourceBytes`, `sourceHash` | SHA-256 drives "identical to the one below" |
| `verdict`, `passedTestCount`, `timeConsumedMs`, `memoryConsumedBytes` | |
| `tests[]`, `testCount`, `compilationError` | **null = never fetched, empty = fetched and withheld.** Not the same thing, and the UI says so |
| `origin` | `SUBMITTED` (we sent it) or `FETCHED` (pulled back) |

Indexes in `mongo-init.js`; `@CompoundIndex` also creates them via `auto-index-creation`.

## 6. API

| endpoint | notes |
|---|---|
| `GET /api/submissions/problem/{contestId}/{index}` | merged list |
| `GET /api/submissions/recent?limit=` | whole archive, newest first |
| `GET /api/submissions/{archiveId}/source` | **local only, never touches the network** |
| `GET /api/submissions/{archiveId}/tests` | fetched on first ask, then archived |
| `GET /api/submissions/codeforces/{contestId}/{submissionId}/source` | fetch + archive |

Tests are a separate call from source on purpose: reading your own code back must stay local,
instant and offline-safe, and folding them together would make every "show me my old code" wait
on the network for something the user may not have asked to see.

## 7. Files

**New — backend**
```
archive/ArchiveDto.java                     Attempt, Source, TestOutcome, TestReport
archive/SubmissionArchive.java              write path, merge, test reports
entity/mongo/CodeSubmission.java            + nested TestOutcome
repository/mongo/CodeSubmissionRepository.java
controller/SubmissionArchiveController.java
test/practice/CfSubmissionParseTest.java    pins the submitSource JSON shape
```

**New — frontend**
```
api/archiveApi.ts
hooks/useArchive.ts
components/editor/SubmissionHistory.tsx     the modal: list + Code/Test results tabs
components/editor/TestReportView.tsx        per-test input / your output / expected
```

**Changed**
```
practice/CfWebSubmitClient.java     fetchSubmission, CSRF cache, parseSubmissionDetail
practice/CfSessionStore.java        cookie allowlist -> denylist
practice/PracticeService.java       archive on submit, verdict sync
compete/CompeteService.java         archive on submit, batch verdict sync
scripts/grab-cf-cookie.py           same cookie filter change
start.sh                            loads .env  (see §9)
mongo-init.js                       code_submissions + indexes
frontend: types/index.ts, SubmitPanel.tsx, usePractice.ts, useCompete.ts,
          PracticePage.tsx, CompetePage.tsx
```

## 8. State

**Verified** — 74 backend tests, 0 failures, 4 skipped (live CF probes needing a session).
Ten new tests pin the JSON shape: CRLF normalisation, numbers-as-strings,
`compilationError: "false"`, withheld mid-contest data, truncation flagged both ways.
All four report branches exercised through the live API; History and the results tabs driven in
a real browser. Cross-user read of another account's archive id → 404; unauthenticated → 401.

**Not verified — needs a live submission.** The chain *submit → verdict → "See the test" →
tests fetched from Codeforces*. Each half is proven separately (parse is unit-tested against
real payload shapes; the fetch pulled real test data by hand) but never joined. Submit something
wrong from Practice and click **See the test**.

## 9. Gotchas

- **`start.sh` now loads `.env`.** Without it the backend fell back to `application.yml`'s
  default port 5432 while compose publishes Postgres on **5433**, connected to whatever else was
  on 5432, and died with `password authentication failed for user "cpintel"`. Already-exported
  variables win, matching how compose treats `.env`.
- The Cloudflare `Just a moment...` page that `curl` gets is a **different** gate from
  Codeforces' own browser check. The JVM and Python clients do not trip the Cloudflare one.
- `Jsoup.text()` collapses whitespace and would return a program on one line with every indent
  gone. `wholeText()` throughout; `CfProbeTest` asserts `lines().count() > 1` to catch it.

## 10. Tomorrow

1. **History panel UI** — flagged as needing work; specifics not yet captured.
2. **Join the live chain** — §8.
3. **`npm run build` still fails** on two unused imports in `DashboardPage.tsx`
   (`useQueryClient`, `StatCard`). Pre-existing, left untouched. Typecheck is otherwise clean.
4. **Worth considering:** `submitSource` also returns per-test data for *sample* tests, so
   "why did my sample fail on the judge but pass locally" is answerable from data already
   fetched. And a draft autosave — buffers currently live in React state, so a crash mid-contest
   loses unsent work that the archive never sees.

## Running it

```bash
./start.sh          # postgres+mongo+redis, backend, CF helper, frontend
```
Frontend http://localhost:5173 · Backend http://localhost:8080 · Swagger `/swagger-ui.html`

Verify the Codeforces scrapers still match reality:
```bash
export CF_COOKIE='<Cookie header from a logged-in codeforces.com tab>'
CF_CONTEST=<id> CF_SUBMISSION_ID=<your submission> \
  ./mvnw test -Dtest=CfProbeTest
```
