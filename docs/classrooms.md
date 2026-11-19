# Classrooms, team contests and the question bank

## Classrooms

A classroom is one DOMjudge instance and everything run on it: the students enrolled on it,
the teams (sections) inside it, and its contests and examinations. Each classroom has its own
judge URL, and no two classrooms share one. A student taking two courses is in two classrooms,
with a different DOMjudge account in each, and both logins are kept side by side.

- **Only a superadmin creates classrooms**, from `/admin/classrooms`: a name, the judge's root
  URL and, optionally, a service account. Saving checks the judge answers. The superadmin also
  archives classrooms and decides which admins run each one, and a classroom can have any
  number of them. An admin sees, and can open by id, only the classrooms they were added to,
  along with those classrooms' teams, events and logins.
- **The audit log follows the classroom.** An admin reads the entries of the classrooms they run
  and their own actions; deployment-wide entries such as other people's sign-ins are for a
  superadmin. The same rule applies to the overview's recent activity and a user's activity.
- **Accounts follow the classroom too.** An admin's user list and user pages show only
  students enrolled in the classrooms they run, plus their own account. Someone new is
  brought in by importing a roster into one of their teams.
- **DOMjudge logins last as long as the enrolment.** They are stored encrypted with the
  student's enrolment in Postgres, so they don't expire mid-semester and they're in the backups.
- **Admins use the console only.** The student pages (dashboard, practice, compete and so on)
  are hidden for admins and superadmins.
- **Enrolment follows from everything else.** Adding someone to a team, importing a roster or
  attaching a login enrols them in that team's classroom. To put a student already in one
  classroom into another, import them into a team in the second classroom as before. The
  username matches their existing account, and the second login is attached next to the first.
- **Students only see their own classrooms.** A classroom's DOMjudge contests refuse anyone
  not enrolled in it. In normal mode a student picks a classroom to see its past exams.
- **One examination at a time**, across all classrooms. The lockout already enforces this.
- **A classroom's judge is fixed once it is in use.** It can't be pointed at another instance
  after students or events are in it, because every login was checked against the first one.
  Archive it and create a new classroom instead.

Inside CPIntel, a DOMjudge contest is named `<classroom>~<contest>` (e.g. `3~demo`), because
contest ids are only unique per judge. You still type the judge's own id in forms; the
qualified form shows up in arena URLs and in the API.

Upgrading a deployment from before classrooms: migration V16 builds a "Default classroom" from
the existing data. Every team and event goes into it, and everyone in them is enrolled. It
starts with no judge: an admin sets its DOMjudge URL from its page. At the next start, the
DOMjudge logins, archived submissions and file rules stored before are moved into it. Nothing
DOMjudge-related is read from `.env`.

## Lab assignments

A lab is a list of Codeforces, LeetCode and CSES problems that a classroom's students solve by a
deadline, on those sites or inside CPIntel. CPIntel reads each student's accepted submissions from their
**verified** handles and fills in a marks grid.

**Setting one** (classroom page → *Lab assignments* → *New lab*):

- Paste problem links: Codeforces problemset, contest and gym links (or shorthand such as
  `1700A`), LeetCode problem links, or CSES problem-set links (or shorthand such as
  `cses:1068`). Each one is looked up when it is added, so a typo is
  caught straight away.
- Give each problem its marks (1 by default).
- Choose when it opens and when it is due. Students see the lab from its opening.
- Choose who it is for: the whole classroom, or only some of its teams.
- *Count problems solved before the lab opened*: off by default. Off means only solves
  between opening and the deadline count. On means any accepted submission before the
  deadline counts.

**Marking.** A solve after the deadline is recorded as *late*: shown, worth no marks. For each
problem, a solve in time beats a late one. Labs are checked every 10 minutes
(`CPINTEL_LAB_CHECK_INTERVAL_MS`), and for a week after their deadline so that late solves
still show. An admin can press *Check now*, and a student can press *Check for new solves*
(once a minute). *Marks (.xlsx)* downloads the grid, every solve with its time (IST), and
the lab's settings.

Changing a lab's dates, or whether earlier solves count, throws its recorded solves away and
checks everyone again under the new rules. Adding, removing, reordering or re-marking
problems keeps the solves of the problems that stay.

**Solving inside CPIntel.** Each unsolved problem on a student's *Labs* page has a **Solve**
button. A Codeforces problem opens in Practice, as any Codeforces problem does (gym problems
are solved on Codeforces). A LeetCode problem opens a workspace with the statement, an editor
holding LeetCode's starter code for the chosen language, **Run** on editable test input, and
**Submit**. Run and Submit go to LeetCode through the student's own LeetCode sign-in: the
desktop app's LeetCode window, or on the website the CPIntel extension (0.4.0 or later,
installed from `/extension`). The server never sees the LeetCode session. The solve lands on
the student's LeetCode account, and an accepted submission asks for their labs to be checked
straight away (once a minute at most; otherwise the next scheduled check picks it up). The
workspace warns when the LeetCode account signed in is not the student's verified handle,
since those solves would not count. Without the app or the extension the workspace still shows
the statement, and the student can solve the problem on LeetCode itself. Premium problems only
show their statement on LeetCode.

**CSES.** CSES has an official API (the one its own command-line client uses), and CPIntel
uses it. A student connects CSES once, on the Platforms page: *Connect CSES* opens cses.fi, where
they sign in to approve CPIntel, and the page notices. No password passes through CPIntel, and
CSES itself says whose account it is, so a connected CSES account needs no further proof. The
token is stored sealed with `CPINTEL_SESSION_KEY` (CSES cannot be connected without it), is never
returned by any endpoint, and is signed out on CSES when the student disconnects. Lab checks read
the student's task statuses and submission times through it. A CSES problem's *Solve* button
opens a workspace with the statement, **Run** on the examples with the same local runner as
Practice (C++, C, Java, Python 3), and **Submit**, which the server sends to CSES with the
student's token. An accepted submission counts for the lab at once. If CSES stops accepting the
token (for example, the student signed out of it on cses.fi), the account shows as needing to
reconnect and its solves are not counted until it is. Submission times without a zone are read
as Helsinki time, which is where CSES runs.

**Verified handles.** Linking a handle only checks that it exists, so labs credit a handle
only after the student proves it is theirs, on the Platforms page:

- Codeforces: within 20 minutes, submit code that does not compile to problem 4A. A
  compilation error does not touch the rating.
- LeetCode: put the code CPIntel shows into the profile *Summary* and save. It can be removed
  once verified.

Handles linked before labs existed stay linked but show as unverified until proven. The lab
grid shows each student's handles and flags anyone whose solves are not being counted.

**Limits.**

- LeetCode's public API only shows a user's last 20 accepted submissions. The 10-minute
  schedule keeps up with normal solving. With *earlier solves* on, an old LeetCode solve only
  counts if it is still among those 20, so a student may need to resubmit it.
- A Codeforces problem is matched by the contest it was linked from. Div. 1 and Div. 2 often
  share a problem under two contest ids, and solving it under the other id does not count.
- Codeforces private group contests are not readable through a student's handle.
- Only LeetCode.com is supported (not leetcode.cn).
- CSES: only the main problem set, not its other courses or contests. The API describes itself
  as having "no stability guarantees", so a change on CSES's side could need a change here.

## Classroom dashboards

**Dashboard** on a classroom's page (`/admin/classrooms/{id}/dashboard`) shows how the class is
doing:

- **Headline:** share of set lab problems solved in time, attendance at events that have
  started, and how many students need a look (missed an event, or solved nothing in a closed
  lab).
- **Labs:** per lab, the average solved and average marks, how many finished it, and how many
  solved nothing.
- **Contests and examinations:** per event, turn-up, average score out of the paper's total,
  average solved, and focus losses.
- **Students:** one row per student. Each lab shows solved out of set, and each event shows a
  score and rank, or *absent*. The roster can be sorted and narrowed to those needing a look.

A student's name opens their page (`/admin/classrooms/{id}/students/{userId}`). It lists every
lab problem with its status and solve time, and every contest or examination question by
question, with marks, wrong attempts, rank, submissions and focus losses.

Scores are read from each event's **last built leaderboard**, which includes TA hand marks.
Opening a dashboard never recomputes a board or calls the judge, so a figure can lag until the
event's leaderboard is refreshed or the event is marked done. Contests ranked on an outside
board fall back to their cached standings.

Only the classroom's own work appears. A student's roadmap, gauntlet and recommendations are
private and are not shown to staff.

### Exports

All three are Excel files, with times in IST.

- **Class report.** Click **Class report (.xlsx)** on the dashboard
  (`/api/v1/admin/classrooms/{id}/report.xlsx`). Its sheets are:
  - **Summary**
  - **Students**: one row per student; each lab shows the number solved, each event shows a
    score, *absent*, *upcoming* or *not assigned*.
  - **Labs** and **Events**: the dashboard tables.
  - **Lab problems**: one row per student per lab problem, with status, solve time and marks.
  - **Event questions**: one row per student per question, with marks, accepted, and wrong
    attempts.
- **Event report.** Click the download button on an event's row
  (`/api/v1/admin/classrooms/{id}/events/{eventId}/report.xlsx`). It lists every student the
  event was set for, absentees included: attended, rank, score, marks per question, wrong
  attempts, submissions and focus losses. It is built from the same stored leaderboard as the
  dashboard. The event's own leaderboard spreadsheet and results zip are still on the event
  page.
- **Lab marks.** Click the download button on a lab's row. This is the same spreadsheet as the
  one on the lab page.

A word stands in for a missing mark (*absent*, *upcoming*, *not set*), so a student who did
not sit is never read as a zero.

## Running a team contest

1. `/admin/teams` — create a team and add its members. For a whole class, the **Import a
   roster** panel on the group page takes a CSV file or a range pasted straight out of Excel or
   Sheets (that paste is tab-separated, and the parser detects which it is given). The first row
   names the columns; `email` or `username` is required, `fullName`, `cfHandle`, `teamName`,
   `djUsername` and `djPassword` optional. DOMjudge columns attach that login in the same pass
   (the preview checks each one against the judge), and a new account takes the DOMjudge
   username as its CPIntel username. A paste of just the login columns re-attaches expired
   logins. **Preview** first — it writes nothing and reports per row what would happen. Rows
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

   DOMjudge passwords can be changed from CPIntel — for one person from their account dialog,
   or for a whole team from the group's panel. Only the password is replaced, and only after the
   judge accepts it; a rejected one leaves the old one in place.

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
4. Members see it under `/teams`, and sit it on `/compete` as normal. An event's own start and
   end decide when it is open, not the judge contest's, so one long-running DOMjudge contest can
   carry several rounds. On Codeforces they
   paste the contest link; on DOMjudge they pick from the list of contests their attached
   account may enter. Statements, editor, submissions, live rank and the board all appear
   there. Nothing about a team contest is monitored or password-protected — see
   [Running an examination](examinations.md#running-an-examination) for the round that is.
5. `/admin/teams/{id}` lists the team's contests; opening one shows the team's ranking. Standings refresh on a timer while the contest is live, or on
   demand.

## Question bank

Exam questions can be written in CPIntel instead of on DOMjudge, at **Question bank** in the
sidebar. A classroom's bank is open to its admins and its TAs. TAs can only read it until an
admin ticks **Can add questions to the bank** on the classroom page; putting questions on the
judge is always the admins' step.

- **Writing.** A question has a title, a statement in Markdown with `$…$` maths (a live
  preview shows the PDF candidates will read), time and memory limits, how output is compared
  (exact, ignoring case, or numbers within a tolerance), a model solution, and its sample and
  secret tests (`name.in` with `name.ans`). A ready-made PDF can replace the written statement.
  Two people saving the same question cannot overwrite each other unknowingly; every edit is in
  the audit trail.
- **Packages.** **Import package** takes the zip DOMjudge imports (problem.yaml,
  domjudge-problem.ini, problem_statement/, data/sample, data/secret, submissions/accepted), up
  to 72 MB; **Template** downloads a complete example with a README on the format; **Download
  package** gives a question back in the same form.
- **Checking.** **Check** runs the model solution on every test in the code runner, under the
  question's own limits, and compares each output the way the judge will. A test passes when the
  output matches, the run is clean, and it took at most half the time limit. It can first fill
  in missing answers from the model solution. The check is optional: a pass marks the question
  **checked**, and any edit makes it a draft again. Checks run one at a time, so they never crowd out candidates' runs.
- **Publishing.** On a DOMjudge examination's or contest's **Questions** tab an admin picks
  questions in order and publishes; each needs at least one test, and every test an answer
  (a passing check is not required). CPIntel creates the contest the examination names (once; it never
  publishes into a contest it did not create), uploads each question as `cpi-e<exam>-q<question>`,
  labels them A, B, C…, and sets the examination's problem list, keeping marks already given.
  Publishing again before the start brings the judge in line with the choice.
- **During the paper.** The problems are fixed, but a corrected question can
  replace its published version in place (same problem, same label), with a notice to every
  candidate; then rejudge that problem. **Notices to candidates** can also be sent on their own;
  they appear in the examination within about a minute.

What the judge needs, and what its API cannot do:

- The classroom's service account must have DOMjudge's **admin** role; on 9.0 the narrower
  roles are refused for creating contests and problems.
- An imported contest is **open to every team** on the judge (only DOMjudge's web pages can
  narrow it): the judge should hold only this course's teams.
- Its duration cannot be changed afterwards, so it is created to run three hours past the
  examination's end; an extension beyond that is made in DOMjudge's jury pages. Its start can be
  moved (by publishing again) until shortly before it.
- A contest cannot be deleted through the API; one made by mistake stays on the judge.

`scripts/question-bank-e2e.py` runs all of this against a real judge (see its header).
