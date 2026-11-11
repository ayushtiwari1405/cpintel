# Running an examination

1. `/admin/exams` — create it: choose the judge, then give a name and a start and end. On
   DOMjudge, CPIntel names the contest and creates it when you publish its questions (or you
   link one already on the judge). On Codeforces you paste the contest link; candidates submit
   through their own connected account, and problems and marks are set once it starts.
   A **private group contest** (`codeforces.com/group/<code>/contest/<id>`) also needs a group
   manager's API key (codeforces.com/settings/api) saved on the classroom page: the public API
   refuses group contests, so verdicts and problems are read with it. Candidates must join the
   group on Codeforces. They connect Codeforces in CPIntel beforehand, or from the exam page
   itself (its **Sign in to Codeforces** button; candidates may enter before the start for
   this). The exam's **People** tab shows who has connected, and **Check candidates** opens
   the contest as each of them to find anyone not in the group; it also warns when the
   contest's own window does not cover the paper's. Codeforces now asks for an anti-bot check
   on submitting: the desktop app and the extension pass it in a small window on the submit
   page, which usually needs no click.
   It is created as a **draft**: invisible to candidates however close its start time is, so
   saving a half-written paper is never the same click as publishing it to two hundred people.
2. **Who sits it** — assign teams, individuals, or both. An examination for four candidates who
   missed the first sitting needs no team at all.
3. **Problems** — the tab reads the linked DOMjudge contest and lists its problems; you give
   each one its marks and save. The leaderboard ranks by total marks, then time. A solved problem
   earns all its marks (the judge gives a verdict, not a partial score); with no marks set at
   all every problem counts as one, and once any are set a problem left blank is worth nothing.
   **Reload from DOMjudge** re-reads the judge's list and keeps the marks you already gave. The
   judge is read through the service account, or else as an assigned candidate's attached login —
   if neither exists, enter the problems by hand using the judge's labels (A, B, C…), since
   submissions are recorded under those. For a contest set up on DOMjudge, statements, test
   data and verdicts stay there. For questions written in the **question bank** ([classrooms.md](classrooms.md#question-bank)), CPIntel
   is the source and the judge holds the published copy: the **Questions** tab publishes them
   and fills this list in for you.
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
   recorded, and which desktop restrictions the examination asks for. Separately, at the top of
   the examination's page, **Require the desktop app** makes the server refuse an examination
   sign-in from a browser (see docs/ARCHITECTURE.md, "Desktop app only"); it can be switched
   while the paper runs. Every restriction is a
   request: the desktop build applies what the operating system allows and reports what it
   could not, and a browser applies almost none of them — which is why an examination sat in a
   browser records that its monitoring was the weaker kind rather than showing a clean sheet.
6. **Passwords** — the tab that produces what you carry into the room. **Issue passwords**
   gives every assigned candidate an examination sign-in password for this paper; download the
   spreadsheet (`.xlsx`, one column per field) and print the desk slips from it. Optionally
   **generate** a room password as well — one string for the whole paper, read out when you
   start it.

   The sign-in password says *the person typing this is the person this seat belongs to*, and
   it is what puts them in examination mode. The room password, if you use one, says *this
   sitting has begun*: by the time the paper starts everybody in the room has it, so on its own
   it cannot tell a candidate from somebody who heard it read out through a door.

   **Neither is ever emailed, and there is no route that would.** The credential in somebody's
   mailbox gets them into CPIntel; getting into the paper additionally needs something only you
   can hand them. Mailing an examination password would collapse the two, and a candidate at
   home with a phone would have everything they needed.

   Issue is additive, so a candidate added an hour before the sitting gets a code without
   invalidating the two hundred slips you have already printed. A slip that goes missing at
   eleven is a **Reissue** on that one row. A password that reaches the wrong room is a
   **Rotate**, which — unlike simply telling people the new one — ends every session that was
   opened with the old one.

   Requires `CPINTEL_EXAM_PASSWORD_KEY`. Without it the tab says so, and nobody can sit the
   examination, because examination mode can only be entered with an issued password.
7. **Publish.** From here the clock owns it: it opens when its window opens and closes when the
   window closes. "End now" moves the window rather than setting a flag, so the arena and this
   screen cannot disagree about whether submissions are open.
8. Candidates sign in on the ordinary login page with their username and the examination
   sign-in password from their slip, and land straight on the paper — there is no navigation in
   examination mode. If the paper has a room password they get one more screen asking for it.
   Before the start, a candidate who signs in with their own password is in normal mode, where
   the examination shows only a note telling them to use the examination password. Once the
   paper has started their own password is refused outright until it ends, and any normal
   session they left open is signed out.

   Once through, they work in the same workspace a contest uses. They are told they are being
   monitored, told the threshold, and warned while they are away rather than afterwards. When
   they are done, *I have finished* records it and signs them out.
9. **Monitor** and **Session log** tabs, during and after. The session log opens on two lists —
   **Students** (everyone assigned, with their counts; click one for their own log, filterable
   by event type) and **Suspicious activity** (computed on read; thresholds under
   `cpintel.exams.flags.*`). It is kept for `CPINTEL_EXAM_RETENTION_DAYS` days. The
   **Passwords** tab keeps working while the paper runs — reissuing one candidate's code, or
   making somebody unlock again after you move them to another machine.
10. **Leaderboard** tab: on or off, how often it is recomputed (default every 15 minutes), the
   penalty per wrong attempt, and whether the final standings are released. **Recompute** does
   it now. Candidates see it in the workspace's Leaderboard tab ("Leaderboard disabled" when it
   is off).
11. Once it has ended, the candidate signs in normally and finds it under **Exams → Past
   examinations**, which opens onto the code they submitted and, if you released them, the final
   standings. Marks are not published by this; that stays yours to decide. The examination
   sign-in password stops working when the paper ends.
12. **Download Excel** on the Leaderboard tab saves the board as you see it, as an .xlsx, at
   any time once the event has started.
   **Rejudge a problem**, on the same tab, is for after a problem's tests or limits were
   corrected on DOMjudge: pick the problem and CPIntel sends every submission made to it during
   the event to the judge again — as the person who wrote it, in the order they were first sent
   — then re-ranks the leaderboard on the new verdicts. Each attempt keeps its original time.
   It runs in the background and says when it has finished how many were sent and how many
   verdicts changed. It needs each person's DOMjudge login to still be attached, and the judge
   contest to still accept submissions; on DOMjudge's own pages these show as new submissions.
13. When it is over and any re-evaluation on the judge has finished, press **Mark as done**
   (contests have it too). CPIntel reads everybody's verdicts from the judge one last time,
   fixes the leaderboard as it then stands, and builds an export in the background: a zip with
   a `submissions/<username>/` folder per person — each attempt named for its problem, attempt
   number and verdict — and `leaderboard.xlsx` holding the board, a list of every submission
   and the event's details (times in it are IST). **Download export** appears when it is ready. **Build again**
   repeats it after a later re-evaluation; **Reopen** takes the mark back and lets the board
   move again. Anything the judge would not hand over is said on the page and in the workbook.
14. **Archive** puts a finished event out of everybody's way and **Unarchive** brings it back.
   Its record stays: the session log, the leaderboard and the export all remain downloadable,
   and a done event cannot be deleted.

## Rehearsing with 200 simulated contestants

`scripts/simulation/` builds a whole fake contest — six problems as DOMjudge-importable
packages, 200 participants wired into both systems, and a driver that plays all 200 at once
and reports latency percentiles, error rates and submit-to-verdict times.

```bash
./scripts/simulation/selftest.py          # prove the harness works, needs nothing running
./scripts/simulation/make_problemset.py --verify
./scripts/simulation/make_roster.py --count 200
```

See [`scripts/simulation/README.md`](../scripts/simulation/README.md) for the rest. Worth doing
before the first real round: it is the only way to find out what 200 people actually cost
before 200 people are in the room.

## Before a DOMjudge round

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


## Restricting an examination to certain languages

An examination can be set to accept only some languages — see
[Running an examination](#running-an-examination). The restriction holds in three places from
one setting: the editor's submit picker, the server's submit endpoint, and the Run button, which
refuses a language the paper does not take rather than letting somebody rehearse in it for an
hour. Hiding the option alone would stop an honest mistake and nothing else, since the submit
endpoint is a plain authenticated POST.
