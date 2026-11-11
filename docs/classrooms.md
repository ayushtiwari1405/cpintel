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
