# Bug report

Defects found by end-to-end testing of CPIntel at commit `5ff7699` on 26 November 2026, using
Playwright and bypass testing: 27 in total (1 critical, 3 high, 9 medium, 14 low). All were open
when this report was written. Source references are paths in this repository at that commit.

Status: all 27 fixed on 27 November 2026; see [bug-fix-plan.md](bug-fix-plan.md) for how each was
checked. The sections below describe the defects as they were found.

Test case IDs refer to the
[end-to-end test suite](https://github.com/ayushtiwari1405/testing_cpintel_e2e). To reproduce a
defect there, run `npm run stack:up`, then for example `npx playwright test --grep "TC-BPC-22"`.
The suite starts its own throwaway copy of the application; do not reproduce these against a
deployment or a database you care about.

## Summary

| ID | Severity | Title | Found by | Status |
|---|---|---|---|---|
| [BUG-001](#bug-001) | Critical | A name differing only in capitals locks the owner out | value and parameter bypass | Fixed |
| [BUG-002](#bug-002) | High | "End" publishes a draft examination and can reveal its questions | control-flow bypass | Fixed |
| [BUG-003](#bug-003) | High | Archiving a draft reveals its questions to candidates | control-flow bypass | Fixed |
| [BUG-004](#bug-004) | High | A candidate can be removed from the classroom during their examination | control-flow bypass | Fixed |
| [BUG-005](#bug-005) | Medium | A NUL character causes an internal server error | value bypass | Fixed |
| [BUG-006](#bug-006) | Medium | A long email address causes an internal server error at registration | value bypass | Fixed |
| [BUG-007](#bug-007) | Medium | Simultaneous identical creations return server errors | concurrency | Fixed |
| [BUG-008](#bug-008) | Low | A team can be renamed to another team's name | parameter bypass | Fixed |
| [BUG-009](#bug-009) | Low | Sign-in shows only "Validation failed" | browser bypass | Fixed |
| [BUG-010](#bug-010) | Medium | The file library limit is exceeded by simultaneous uploads | concurrency | Fixed |
| [BUG-011](#bug-011) | Medium | A wrong current password is submitted twice | browser workflow | Fixed |
| [BUG-012](#bug-012) | Medium | "Check your email" is shown when the request failed | network failure | Fixed |
| [BUG-013](#bug-013) | Low | Profile save errors are reduced to "Update failed" | form validation | Fixed |
| [BUG-014](#bug-014) | Low | Password recovery fields and a button have no accessible name | accessibility | Fixed |
| [BUG-015](#bug-015) | Low | A too-long password is reported as too short | value bypass | Fixed |
| [BUG-016](#bug-016) | Low | The server accepts addresses the console form rejects | value bypass | Fixed |
| [BUG-017](#bug-017) | Low | `_` and `%` act as wildcards in the user search | value bypass | Fixed |
| [BUG-018](#bug-018) | Low | A `javascript:` avatar address is stored | value bypass | Fixed |
| [BUG-019](#bug-019) | Medium | Negative marks are accepted for an examination problem | value bypass | Fixed |
| [BUG-020](#bug-020) | Low | Signing out leaves other sessions' access tokens working | session testing | Fixed |
| [BUG-021](#bug-021) | Low | A deactivated account is revealed without its password | unauthorized access | Fixed |
| [BUG-022](#bug-022) | Low | Response time reveals whether an account exists | unauthorized access | Fixed |
| [BUG-023](#bug-023) | Low | A locked roadmap skill can be marked completed | control-flow bypass | Fixed |
| [BUG-024](#bug-024) | Medium | Items inside lists are never validated | value bypass | Fixed |
| [BUG-025](#bug-025) | Medium | Admin lists show nothing when loading fails | network failure | Fixed |
| [BUG-026](#bug-026) | Low | A lost session renewal signs the user out | network failure | Fixed |
| [BUG-027](#bug-027) | Low | A classroom created while the list loads does not appear | slow network | Fixed |

Severity: **Critical** one user can deny another the application; **High** examination integrity
or confidentiality; **Medium** wrong behaviour, bad data or a server error; **Low** minor,
cosmetic or defence in depth.

---

## BUG-001

**A name differing only in capitals locks the owner out** · Critical ·
Tests: TC-AUTH-04, 05, 06, TC-BPP-16

- **Steps:** An account `stu_ab` exists. Register `STU_AB` (or the same email in capitals). Sign in
  as the original owner with the correct password.
- **Expected:** The registration is refused with 409.
- **Actual:** Registration succeeds with 201. The owner's sign-in then returns **500**, every time.
  An administrator editing an account's email can create the same clash.
- **Cause:** Uniqueness is checked case-sensitively (`existsByEmail`, `existsByUsername`,
  `backend/src/main/java/com/cpintel/service/AuthService.java:72-75`; `backend/src/main/java/com/cpintel/admin/AdminUserService.java:241-293`) and the database
  `UNIQUE` constraints are case-sensitive. Sign-in looks names up ignoring case and expects one
  row (`AuthService.resolve`).
- **Impact:** Where self-registration is enabled, anyone who knows a username can block that
  account, including an administrator's. Registration is off by default.
- **Fix:** Add unique indexes on `lower(username)` and `lower(email)` and check ignoring case.

## BUG-002

**"End" publishes a draft examination** · High · Tests: TC-BPC-22, 23

- **Steps:** Create an examination and leave it a draft. Send
  `PUT /api/v1/admin/events/{id}/lifecycle` with `{"lifecycle":"ENDED"}`.
- **Expected:** 400, as a draft was never running.
- **Actual:** 200. The draft becomes visible to its candidates. If its planned start has passed,
  its problem list is readable. If the start is in the future, it is saved with an end before its
  start (duration −86400 seconds).
- **Cause:** `backend/src/main/java/com/cpintel/events/EventService.java:374-382` sets the end to now and the state to
  `SCHEDULED` without checking the current state.
- **Fix:** Allow `ENDED` only from `ACTIVE`.

## BUG-003

**Archiving a draft reveals its questions** · High · Test: TC-BPC-24

- **Steps:** Create a draft with a candidate and problems. Archive it. As the candidate, request
  `GET /api/v1/exams/{id}`.
- **Expected:** The unpublished paper stays hidden.
- **Actual:** 200 with the problem titles, even when the examination was planned for the future.
- **Cause:** `EventService.java:384-390` allows `DRAFT → ARCHIVED`; candidate access excludes only
  drafts, and `myExam` treats archived papers as readable.
- **Fix:** Keep never-published events hidden, for example by refusing to archive a draft.

## BUG-004

**A candidate can be removed from the classroom during their examination** · High ·
Tests: TC-BPC-37, 38

- **Steps:** While a team's examination runs, call
  `DELETE /api/v1/admin/classrooms/{room}/members/{user}`.
- **Expected:** 409, as removing the same person from the *team* is refused (TC-BPC-36 passes).
- **Actual:** 200. The candidate's next request for the paper returns 404: locked out mid-exam.
- **Cause:** `backend/src/main/java/com/cpintel/classrooms/ClassroomService.java:358` deletes team memberships directly,
  bypassing `GroupService.refuseWhileSitting`.
- **Fix:** Apply the same check before removing a classroom member.

## BUG-005

**A NUL character causes a server error** · Medium ·
Tests: TC-BPV-28, 38, 62, 68, 82

- **Steps:** Send `"\u0000"` inside the full name (registration, admin create, profile), a team
  name, or the sign-in identifier.
- **Expected:** 400.
- **Actual:** 500 `INTERNAL_ERROR`. The sign-in case needs no authentication.
- **Cause:** No input check; PostgreSQL cannot store the character, and the error falls to the
  catch-all handler.
- **Fix:** Reject control characters during request validation.

## BUG-006

**A long email address causes a server error** · Medium · Test: TC-BPV-27

- **Steps:** Register with a valid address of about 310 characters.
- **Expected:** 400.
- **Actual:** 500.
- **Cause:** `RegisterRequest.email` has `@Email` but no `@Size(max = 255)`
  (`backend/src/main/java/com/cpintel/dto/AuthDto.java`); the column is `VARCHAR(255)`. The admin form's request has the limit.
- **Fix:** Add the size constraint.

## BUG-007

**Simultaneous identical creations return server errors** · Medium ·
Tests: TC-CON-01, 02, 04

- **Steps:** Send the same registration or account creation 8 times at once, or add one member to
  a team 24 times at once.
- **Expected:** One success; the others 409.
- **Actual:** One success; the others **500**. Data stays correct. TC-CON-04 is timing dependent
  and does not fail on every run.
- **Cause:** Check-then-insert; the unique-constraint violation of the losers is not handled.
- **Fix:** Map `DataIntegrityViolationException` for unique constraints to 409.

## BUG-008

**A team can be renamed to another team's name** · Low · Test: TC-BPP-14

- **Steps:** With teams A and B in one classroom, update B's name to A's.
- **Expected:** 409, as creating a duplicate is refused.
- **Actual:** 200; two teams share a name.
- **Cause:** `backend/src/main/java/com/cpintel/groups/GroupService.java:103` does not repeat the creation check, and no database
  constraint exists.
- **Fix:** Add the check and a unique index on `(classroom_id, lower(name))`.

## BUG-009

**Sign-in shows only "Validation failed"** · Low · Test: TC-UI-BP-02

- **Steps:** Enter only spaces in both sign-in fields and submit.
- **Expected:** A message naming the problem field.
- **Actual:** "Validation failed". The field messages are in the response's `errors` object.
- **Cause:** `frontend/src/hooks/useAuth.ts:25` shows only `message`.
- **Fix:** Show the field errors, or trim and check the values in the form.

## BUG-010

**The file limit is exceeded by simultaneous uploads** · Medium · Test: TC-FILE-18

- **Steps:** With a 5-file limit, upload 20 different files at once.
- **Expected:** At most 5 stored.
- **Actual:** More than 5 stored (7 and 9 in two observed runs).
- **Cause:** `checkQuota` counts and then saves without a lock
  (`backend/src/main/java/com/cpintel/files/PersonalFileService.java:236`). The total-size limit uses the same pattern.
- **Fix:** Serialise uploads per user, or reserve quota atomically.

## BUG-011

**A wrong current password is submitted twice** · Medium · Test: TC-UI-PW-13

- **Steps:** Profile → enter a wrong current password and a valid new one → submit once.
- **Expected:** One request.
- **Actual:** Two requests to `/auth/change-password`, with a session renewal between them.
- **Cause:** The server answers a wrong current password with **401**. The client treats any 401 as
  an expired session, renews and retries; `change-password` is missing from `SIGNING_IN`
  (`frontend/src/api/session.ts:40`).
- **Impact:** Each attempt costs two of the 5 password operations allowed per 15 minutes per
  address (default settings), a limit shared with password reset and registration.
- **Fix:** Answer 400 or 403 for a wrong current password.

## BUG-012

**"Check your email" is shown when the request failed** · Medium ·
Tests: TC-UI-NET-10, 11, 12

- **Steps:** Ask for a reset link while offline, or while the server answers 500 or 429.
- **Expected:** An error, or the form kept for another try.
- **Actual:** "If … has an account, a reset link is on its way". Nothing was sent.
- **Cause:** `frontend/src/pages/ForgotPasswordPage.tsx:30` uses `onSettled`, which also runs on
  failure.
- **Fix:** Use `onSuccess` and show an error otherwise. Failures that do not depend on the account
  can be reported without revealing whether it exists.

## BUG-013

**Profile save errors are reduced to "Update failed"** · Low · Test: TC-UI-PW-15

- **Steps:** Save a full name of 101 characters.
- **Expected:** The limit is explained, or the field prevents it.
- **Actual:** "Update failed". The field has no `maxLength`.
- **Cause:** `frontend/src/pages/ProfilePage.tsx:27` discards the server's reply.
- **Fix:** Show the field error and set `maxLength={100}`.

## BUG-014

**Missing accessible names on password recovery** · Low · Tests: TC-UI-A11Y-02, 03, 04

- **Steps:** Inspect `/forgot-password` and `/reset-password`.
- **Expected:** Every field and button has an accessible name.
- **Actual:** The email, new-password and repeat fields are not associated with their labels, and
  the show-password button has no name. The sign-in page does this correctly.
- **Fix:** Add `htmlFor`/`id` pairs and an `aria-label`.

## BUG-015

**A too-long password is reported as too short** · Low · Test: TC-BPV-26

- **Steps:** Register with a password of more than 200 characters.
- **Expected:** A message about the maximum.
- **Actual:** "A password has to be at least 10 characters".
- **Cause:** One `@Size` annotation carries both limits and one message (`AuthDto.java`).
- **Fix:** Give the maximum its own message.

## BUG-016

**The server accepts addresses the console form rejects** · Low · Test: TC-BPV-61

- **Steps:** Send `name@localhost` to `POST /api/v1/admin/users`.
- **Expected:** 400, matching the dialog, which requires a top-level domain.
- **Actual:** 201. Such an account cannot receive a reset link from a public mail server.
- **Fix:** Use one rule on both sides.

## BUG-017

**`_` and `%` act as wildcards in the user search** · Low · Tests: TC-BPV-110, 111

- **Steps:** Search for `abc_x`, or for `%`.
- **Expected:** Literal matches only. Usernames may contain `_`.
- **Actual:** `abc_x` also matches `abc9x`; `%` matches every account.
- **Cause:** `backend/src/main/java/com/cpintel/admin/AdminUserService.java:585` builds a `LIKE` pattern without escaping. This is
  not SQL injection: the value is bound as a parameter.
- **Fix:** Escape `\`, `%` and `_`.

## BUG-018

**A `javascript:` avatar address is stored** · Low · Test: TC-BPV-67

- **Steps:** Set `avatarUrl` to `javascript:alert(document.cookie)`.
- **Expected:** 400.
- **Actual:** Saved. No page currently renders it as a link or image source, so it is not
  exploitable today.
- **Fix:** Allow only `https:` and `http:` addresses (`UserService.java:48`).

## BUG-019

**Negative marks are accepted** · Medium · Test: TC-BPV-97

- **Steps:** Create an examination with a problem worth `-50`.
- **Expected:** 400.
- **Actual:** 201; the negative value is stored and would enter the marks.
- **Cause:** `ProblemRequest.points` has no minimum (`backend/src/main/java/com/cpintel/events/EventsDto.java:178-184`).
- **Fix:** Add `@PositiveOrZero`.

## BUG-020

**Signing out leaves other sessions' access tokens working** · Low · Test: TC-AUTH-12

- **Steps:** Sign in twice; sign out one session; use the other's access token.
- **Expected:** 401, as sign-out revokes every refresh token of the account.
- **Actual:** 200 until the token expires (15 minutes by default).
- **Cause:** `AuthService.logout` blacklists only the presented token and does not call
  `revokeUserTokens`.
- **Fix:** Revoke the user's access tokens too, or revoke only this session's refresh token.

## BUG-021

**A deactivated account is revealed without its password** · Low · Test: TC-AUTH-16

- **Steps:** Sign in to a deactivated account with a wrong password.
- **Expected:** The same 401 as any wrong password.
- **Actual:** 403 "Account is deactivated".
- **Cause:** `AuthService.login` checks the active flag before the password.
- **Fix:** Check the password first.

## BUG-022

**Response time reveals whether an account exists** · Low · Test: TC-AUTH-17

- **Steps:** Compare the median time of failed sign-ins for existing and unknown names.
- **Expected:** Similar times.
- **Actual:** Existing accounts answered about 7 to 10 times slower in the observed runs.
- **Cause:** An unknown name returns before the Argon2 comparison
  (`backend/src/main/java/com/cpintel/service/AuthService.java:139`).
- **Fix:** Verify against a dummy hash when no account is found.

## BUG-023

**A locked roadmap skill can be marked completed** · Low · Test: TC-BPC-40

- **Steps:** Send `PATCH /api/v1/roadmaps/nodes/{id}?status=COMPLETED` for your own locked node.
- **Expected:** Refused while prerequisites are unmet.
- **Actual:** 200. The interface offers no such control.
- **Cause:** `backend/src/main/java/com/cpintel/service/RoadmapService.java:182` checks ownership and the status name only.
- **Fix:** Check prerequisites, or remove the endpoint if unused. It may be intentional; confirm
  with the product owner.

## BUG-024

**Items inside lists are never validated** · Medium ·
Tests: TC-BPV-93, 94, 95, 98, 100, 101, 103

- **Steps:** Create an examination with a 17-character problem label, a 201-character title, a
  101-character problem id or marks of `1e12`. Or, in an examination session, report an event with
  no `eventId`, a 65-character `eventId` or a 501-character `detail`.
- **Expected:** 400.
- **Actual:** 500.
- **Cause:** The lists lack `@Valid`, so the annotations on their elements are ignored
  (`backend/src/main/java/com/cpintel/events/EventsDto.java:186, 254, 422`).
- **Impact:** A candidate's client receives a server error and one bad event fails the batch.
- **Fix:** Declare `List<@Valid ProblemRequest>` and `List<@Valid ClientEvent>`.

## BUG-025

**Admin lists show nothing when loading fails** · Medium · Tests: TC-UI-NET-41, 42, 43

- **Steps:** Open Users, Classrooms or Teams while the list request returns 500.
- **Expected:** An error with a retry, as the dashboard shows.
- **Actual:** An empty table or grid with no message, which reads as "there are none".
- **Cause:** The three pages never read the query's `isError`.
- **Fix:** Render the shared `ErrorState` component.

## BUG-026

**A lost session renewal signs the user out** · Low · Test: TC-UI-NET-34

- **Steps:** Sign in. Let the server renew the session while the browser does not receive the
  reply (a dropped connection, or a reload while `/auth/refresh` is in flight). Reload.
- **Expected:** Still signed in.
- **Actual:** The sign-in page. First seen when a WebKit test reloaded a page that was still
  loading; the test reproduces it deterministically.
- **Cause:** Refresh tokens are single use. The 10 second grace in `backend/src/main/java/com/cpintel/service/AuthService.java`
  (`REUSE_GRACE`) stops the session family being revoked, but still refuses the old token, so a
  client that never got the successor has no way back.
- **Fix:** Within the grace period, answer a retired token with its successor. This is a security
  trade-off to weigh against token theft detection.

## BUG-027

**A classroom created while the list loads does not appear** · Low · Test: TC-UI-WF-06

- **Steps:** On a slow connection, open Classrooms and create one before the list has arrived.
- **Expected:** The new classroom is listed.
- **Actual:** "Created …" is shown, but the list that arrives lacks it, until the page is
  reloaded. An admin may create it again and be told the judge is already in use. First seen as
  an intermittent failure of TC-UI-WF-01 with about 100 classrooms.
- **Cause:** `useCreateClassroom` (`frontend/src/hooks/useClassrooms.ts:142`) invalidates the
  list query. With no data yet, React Query reuses the request already in flight instead of
  starting a new one.
- **Fix:** Cancel the list query before invalidating it.
