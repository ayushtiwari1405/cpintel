# Bug fix plan

Plan for the 27 defects in [bug-report.md](bug-report.md), written against `5ff7699`. All four
batches have been implemented with the recommendations below; see [Outcome](#outcome).

Paths are relative to `backend/src/main/java/com/cpintel/` unless they start with `frontend/`,
`backend/`, `scripts/` or `docs/`.

## Decisions taken

| # | Question | Recommendation |
|---|---|---|
| D1 | BUG-023: refuse the change, or remove `PATCH /roadmaps/nodes/{id}`? | Remove it. The two hooks that call it (`useUpdateRoadmapNode` in `useRoadmap.ts` and `useAnalytics.ts`) have no callers. |
| D2 | BUG-026: replay the successor refresh token within the grace period, or accept the defect? | Replay it, with the limits under BUG-026. It weakens theft handling for 10 seconds. |
| D3 | BUG-001: what if a deployment already holds two accounts differing only in capitals? | Stop the upgrade in preflight and let an administrator rename or merge. Never rename accounts automatically. |
| D4 | BUG-008: the same for duplicate team names | Suffix the later ones with their id in the migration, and log it. |

## Order of work

| Batch | Content | Bugs |
|---|---|---|
| 1 | Account takeover of sign-in, examination integrity | 001, 002, 003, 004 |
| 2 | Server errors, validation, concurrency | 005, 006, 007, 008, 010, 015, 016, 017, 018, 019, 024 |
| 3 | Sign-in and sessions | 011, 020, 021, 022, 023, 026 |
| 4 | Frontend | 009, 012, 013, 014, 025, 027 |

Batch 1 is worth shipping on its own if an examination is close.

## Batch 1

### BUG-001: names differing only in capitals (Critical)

- Migration `V36__case_insensitive_accounts.sql`: unique indexes on `lower(username)` and
  `lower(email)`. It fails with a message listing the clashing user ids if duplicates exist.
- `repository/jpa/UserRepository.java`: write `findByEmailIgnoreCase`,
  `findByUsernameIgnoreCase` and new `exists…IgnoreCase` methods as explicit `lower()` queries.
  The derived `IgnoreCase` methods compare with `upper()`, which would not use these indexes.
- Use the case-insensitive checks everywhere an account is matched: `service/AuthService.java`,
  `admin/AdminUserService.java` (create and update), `groups/RosterImportService.java:311` and
  `:577`, `evaluation/ClassroomTaService.java:92` and `:120`, `admin/AdminBootstrap.java`.
- On update, exclude the account being edited. Otherwise an account stored as `A@x.org` clashes
  with itself when the form lower-cases it.
- `scripts/deploy-preflight.py`: report duplicates before the upgrade starts (D3).
- Tests: register and admin update with changed capitals give 409; owner still signs in; roster
  import links the existing account; schema test for both indexes.

### BUG-002: "End" publishes a draft (High)

- `events/EventService.java`, `ENDED` case: refuse unless the current state is `ACTIVE`.
- `frontend/src/pages/admin/AdminExamDetailPage.tsx:258`: show "End now" only when `ACTIVE`.
  The page also shows it for `SCHEDULED`, so add "Back to draft" there (the server already
  allows it), or an admin loses the way to withdraw a published paper.

### BUG-003: archiving reveals questions (High)

- `EventService`, `ARCHIVED` case: allow only from `ENDED` (or already archived). This is
  stricter than refusing drafts: a published, not yet started paper can be archived today and
  leaks the same way.
- `EventService.myExam`: return problems for `ENDED` or `ARCHIVED` only when the start time has
  passed.
- Migration `V37__repair_unsat_events.sql` for 002 and 003: set back to `DRAFT` any event with
  `ends_at < starts_at`, and any archived event with no start or `archived_at < starts_at`.
- Drafts ended or archived after their planned start cannot be told apart by timestamps. List
  archived and ended examinations with no `exam_events` rows for an administrator to review.

### BUG-004: classroom removal during an examination (High)

- `classrooms/ClassroomService.removeMember`: refuse with 409 if the person is a candidate of any
  running examination in that classroom, however assigned. Removal also deletes their judge login,
  so being named on the paper does not make it safe.
- `GroupService` already depends on `ClassroomService`, so query the repositories directly (or
  move the rule to a small shared guard) to avoid a cycle.
- Same gap, not in the report: `GroupService.deactivate` takes a team inactive, which drops its
  members from a running paper. Apply the team rule there too.
- Tests: team-assigned and directly named candidates refused; allowed before and after the paper.

## Batch 2

### BUG-005: NUL characters

- Reject `U+0000` in incoming JSON with a string deserializer registered on a **copy** of the
  mapper used by the MVC converter (`config/WebMvcConfig.java`). Registering it on the shared
  mapper would also reject judge responses.
- An interceptor rejects it in query and form parameters.
- Answer 400. Only NUL is rejected: tabs and newlines are legitimate in source and statements.

### BUG-006 and BUG-015: email and password limits

- `dto/AuthDto.java`: `@Size(max = 255)` on both email fields.
- Split each password `@Size` into a minimum and a maximum with separate messages, on register,
  reset and change. `admin/AdminDto.java` has the same fault on `SetPasswordRequest`, and
  `CreateUserRequest.password` has no maximum.

### BUG-007: simultaneous creations

- `exception/GlobalExceptionHandler.java`: map `DataIntegrityViolationException` to 409 only for
  unique violations (SQLState `23505`, and Mongo `DuplicateKeyException`). Other integrity errors
  stay 500, so real faults are not hidden.
- The message is generic and never includes the constraint detail.

### BUG-008: duplicate team names

- `groups/GroupService.update`: repeat the creation check, excluding the team itself.
- Migration `V38__unique_team_names.sql`: resolve existing duplicates (D4), keeping within 120
  characters, then add the unique index on `(classroom_id, lower(name))`.

### BUG-010: file limit under concurrent uploads

- `files/PersonalFileService.upload`: hold a per-user lock (a fixed set of striped locks) across
  `checkQuota` and the save. This covers both the count and the size limit.
- This holds for one backend instance, which is what the compose file runs. More instances would
  need a Redis or Postgres lock. `RecomputeLock` is unsuitable: it proceeds when Redis is down.

### BUG-016: address rule

- One pattern requiring a top-level domain, applied to register, admin create and admin update.
  Sign-in is unchanged, so existing accounts keep working.
- Check `scripts/` and test fixtures for `@localhost`. An existing such account must be given a
  real address before an admin can save its form.

### BUG-017: search wildcards

- `AdminUserService.filter`: escape `\`, `%` and `_`, and pass `'\\'` as the `like` escape.

### BUG-018: avatar address

- `dto/UserDto.UpdateRequest.avatarUrl`: allow empty, `http://` or `https://` only.
- Clear stored values that do not match (in `V37`), or those users cannot save their profile.

### BUG-019 and BUG-024: list items and marks

- `ProblemRequest.points`: `@PositiveOrZero` and `@DecimalMax("100000")`, matching hand marking.
- `events/EventsDto.java`: `List<@NotNull @Valid ProblemRequest>` (both places) and
  `List<@NotNull @Valid ClientEvent>`. Same fault in `groups/GroupsDto.ViolationReport`.
- `frontend/src/hooks/useExamSession.ts` keeps a failed batch and retries it, so one invalid event
  would block the whole log (it does today with the 500). Trim fields to the server limits before
  queuing, and on a 400 drop only the events the response names, then resend the rest.

## Batch 3

### BUG-011: wrong current password sent twice

- `service/PasswordService.change`: answer 400 with code `WRONG_CURRENT_PASSWORD`. The audit
  entry and rate limit stay. Update `PasswordServiceTest`.
- Same retry, not in the report: Codeforces session errors also answer 401
  (`practice/CfWebSubmitClient.java:187`, `:391`, `practice/PracticeService.java:167`), so the
  client renews and resends, including submissions. In `frontend/src/api/session.ts`, do not renew
  on `CF_SESSION_INVALID`, and make the connect-session failure a 400.

### BUG-020: other sessions after sign-out

- `AuthService.logout`: also call `jwtService.revokeUserTokens(userId)`. Refresh tokens are
  already all revoked, so this only closes the 15 minute gap.
- Limits kept from the existing design: a token issued under a second earlier survives, and this
  fails open if Redis is down.

### BUG-021 and BUG-022: what sign-in reveals

- `AuthService.login`: verify the password (examination or account) first; report "deactivated"
  only to someone who gave a correct one. A deactivated account must still get no session.
- For an unknown name, run one Argon2 comparison against a hash made at startup.
- Unknown names will then cost the same 64 MB hash as known ones. The existing per-address and
  per-account limits apply; rerun the sign-in load test.

### BUG-023: locked roadmap skill

- Per D1, remove the endpoint, `markNodeProgress`, the two `updateNode` clients and both hooks.

### BUG-026: lost renewal reply (if D2 is yes)

- Derive the successor as an HMAC of the presented token under a server key, so it can be
  recomputed without storing any raw token.
- In `refresh`, for a rotated token inside `REUSE_GRACE`: return the successor only if its row is
  unrevoked and unused, the `User-Agent` matches, and the active-account, examination and
  desktop-app checks pass. Otherwise refuse as now.
- A request losing the atomic claim is refused as now; the client already retries once.
- Revise `RefreshTokenRotationTest`: reuse after the grace period must still end the family.

## Batch 4

| Bug | Change |
|---|---|
| 009 | Add one helper beside `errorMessage` in `components/common/States.tsx` that prefers the response's field errors; use it in `useLogin`. Trim-check both fields in `LoginPage`. |
| 012 | `ForgotPasswordPage`: set `sent` in `onSuccess` only; otherwise keep the form and show the error (offline, 429, 500). None of these depends on the account. Replace the old comment. |
| 013 | `ProfilePage`: show field errors with the same helper; `maxLength={100}` on the name, `500` on the avatar address. |
| 014 | `ForgotPasswordPage`, `ResetPasswordPage`: `htmlFor`/`id` pairs, and an `aria-label` and `aria-pressed` on the show-password button. |
| 025 | Users, Classrooms and Teams pages: render `ErrorState` with retry when the query failed and has no data; suppress the empty message then. |
| 027 | `useCreateClassroom`: `await qc.cancelQueries(...)` before invalidating. Apply the same to team and account creation. |

All paths in this table are under `frontend/src/`. Each row gets a Vitest case.

## Verification

1. After each batch: backend tests (including the Testcontainers schema test), frontend tests,
   lint, type check and build.
2. Update the migration count in `README.md`; `DocumentedCountsTest` fails otherwise. Update the
   docs that describe lifecycle moves, sign-out and password errors.
3. Run the migrations against a copy of real data, including seeded duplicates.
4. Run the named cases in the end-to-end suite on its throwaway stack, then the whole suite, and
   `scripts/integration-test.sh`.
5. Run local CI with `--audit`. Mark each bug's status in `bug-report.md` only once its test
   passes.

## Deploying to the college server

- Back up first, then run the preflight duplicate checks (D3, D4).
- `V37` changes event states: review its list beforehand.
- Do not deploy during an examination.

## Outcome

Implemented 27 November 2026.

**Checked.** The 21 defects reachable through the API were replayed against a build of `5ff7699`
on throwaway databases (all 21 reproduced) and against the fixed build (all 21 gone). The same
dirty database was then upgraded: the check script and `V36` stopped on the duplicate account,
and after renaming it `V36` to `V38` applied. BUG-009, 011 to 014, 025, 027 and the new admin
button were checked in Chrome. Unit and migration tests cover the changes.

**Not run.** The end-to-end suite the report links to is an empty repository, so no `TC-` case was
run. Nothing was run against a real DOMjudge or the college server.

**Where it differs from the plan above.**

- BUG-020: access tokens now record their issue time to the millisecond. With the one second
  allowance, signing out right after another sign-in left that session working.
- BUG-003: `V37` leaves alone any event with a session log or marked done.
- BUG-014: the show-password button changes its label, as on the sign-in page, without
  `aria-pressed`.
- BUG-027: only queries still waiting for their first answer are cancelled.
- D3: the duplicate check is its own script, `scripts/postgres-upgrade-check.sh`. The preflight
  never connects to a database; it points to the script.

**Seen, not changed.** When two creations race, Hibernate still logs the loser at `ERROR` with the
constraint's detail, which can include a username or address.
