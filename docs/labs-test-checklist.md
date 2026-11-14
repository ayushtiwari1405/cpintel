# Lab assignments: manual test checklist

Everything to check by hand for lab assignments (commits `9d1a659`, `f057d3c`, `dfc25f2`):
Codeforces, LeetCode and CSES problems in classrooms, verified accounts, and solving inside
CPIntel. Automated tests and local CI already cover the logic; this list covers what needs real
accounts, real browsers, the desktop app or the college server.

**Status:** ✅ already verified on the local stack (2026-11-15) · ⬜ still to test.
Tick items as you go, and note anything that fails next to it.

---

## 0. Before you start

| # | Item | Status |
|---|---|---|
| 0.1 | An admin who runs a classroom, and at least **two** student accounts enrolled in it (one in a team, one not) | ⬜ |
| 0.2 | A Codeforces account you can submit from | ⬜ |
| 0.3 | A LeetCode account (e.g. the burner; change its password after testing, since it was shared in chat) | ⬜ |
| 0.4 | A CSES account (cses.fi) | ⬜ |
| 0.5 | CPIntel extension **0.4.0** installed from `/extension` (remove 0.3.0 first; Chrome asks for the new LeetCode and cookies permissions) | ⬜ |
| 0.6 | A desktop app build from this code, for the desktop-app sections | ⬜ |
| 0.7 | `CPINTEL_SESSION_KEY` set on the server (CSES cannot connect without it) | ⬜ |

## 1. Admin: setting labs

| # | Steps | Expected | Status |
|---|---|---|---|
| 1.1 | Classroom page → *Lab assignments* panel → *New lab* | Editor opens with title, instructions, opens/due dates, earlier-solves toggle, teams, problems | ✅ |
| 1.2 | Add a Codeforces link (problemset, contest, and `1700A` shorthand) | Real title and rating appear | ✅ |
| 1.3 | Add a Codeforces **gym** link | Accepted with a "Gym … problem …" title | ⬜ |
| 1.4 | Add a LeetCode link | Real title and difficulty; Premium problems marked "(Premium)" | ✅ (non-premium) / ⬜ (premium) |
| 1.5 | Add a CSES link and `cses:1068` | Real task name | ✅ |
| 1.6 | Add a bad link, an unknown CF/LC/CSES problem, and the same problem twice | Clear error each time; nothing added | ✅ |
| 1.7 | Set marks per problem, reorder, remove one, save | Lab page shows the order and marks; total is right | ✅ |
| 1.8 | Deadline before opening | Refused with a message | ✅ |
| 1.9 | Tick one team only | Only that team's students appear in the grid and see the lab | ⬜ |
| 1.10 | No team ticked | Every enrolled student is set the lab | ✅ |
| 1.11 | Edit the dates or the earlier-solves toggle | Warning that everything is rechecked; solves reappear after the check | ✅ |
| 1.12 | Edit only marks/order/problems | Existing solves stay | ✅ |
| 1.13 | *Check now* twice quickly | First starts a check; second says one is already running | ✅ |
| 1.14 | *Marks (.xlsx)* | Opens in **Excel / LibreOffice / Google Sheets**: Marks, Solves (IST times), Lab sheets | ✅ (file structure) / ⬜ (open in Excel) |
| 1.15 | Delete a lab | Gone, with its solves; audit trail shows created/updated/deleted | ✅ |
| 1.16 | A second admin who does **not** run the classroom opens its lab URLs | Refused | ⬜ |
| 1.17 | A student opens an admin lab URL | Refused (403) | ✅ |
| 1.18 | Archived classroom | Its labs disappear from students' Labs page | ⬜ |
| 1.19 | Lab opening in the future | Students don't see it until it opens; admin sees "Upcoming" | ⬜ |

## 2. Verifying handles (Platforms page)

| # | Steps | Expected | Status |
|---|---|---|---|
| 2.1 | Link a LeetCode handle that doesn't exist | Refused | ✅ |
| 2.2 | Link a real LeetCode handle | Linked, shown as **not verified**; no "Sync" button | ✅ |
| 2.3 | LeetCode: *Verify this handle* → put the code in your LeetCode profile **Summary** → *Check* | Shows "Verified" | ⬜ (refusal without the code ✅) |
| 2.4 | Codeforces: *Verify* → submit code that **doesn't compile** to problem 4A within 20 min → *Check* | Shows "Verified" | ⬜ |
| 2.5 | Wait more than 20 minutes after starting a proof, then *Check* | "Expired, start a new one" | ⬜ |
| 2.6 | Link a handle already linked to another CPIntel account | Refused | ⬜ |
| 2.7 | Codeforces handles linked **before** this release | Show as unverified until proven | ⬜ |
| 2.8 | Unlink and re-link a different handle | Starts unverified again | ⬜ |

## 3. Lab checks: are solves picked up?

| # | Steps | Expected | Status |
|---|---|---|---|
| 3.1 | Solve a Codeforces lab problem **on codeforces.com** inside the window | Grid shows ✓ within 10 min (or after *Check now*) | ✅ (with a public account) |
| 3.2 | Solve after the deadline | Shows **L** (late), no marks | ✅ |
| 3.3 | Problem solved **before** the lab opened, toggle off / on | Off: not counted. On: counted | ✅ |
| 3.4 | Solve a LeetCode lab problem **on leetcode.com** | Counted within 10 min | ✅ (with a public account) |
| 3.5 | Student with an **unverified** handle solves | Not counted; grid and student page say why | ✅ |
| 3.6 | Leave the server running for 10+ minutes without pressing anything | Scheduled check runs ("Checked x minutes ago" updates) | ⬜ |
| 3.7 | Late solves up to 7 days after the deadline | Still recorded; nothing after 7 days | ⬜ |
| 3.8 | Student *Check for new solves* twice within a minute | Second one says to wait | ✅ |
| 3.9 | Class-sized lab (30+ students): time one *Check now* | Finishes in a few minutes; no errors in the backend log | ⬜ |

## 4. Solving LeetCode problems inside CPIntel

Run each with the **website + extension 0.4.0** and with the **desktop app**.

| # | Steps | Expected | Status (web / desktop) |
|---|---|---|---|
| 4.1 | Labs page → *Solve* on a LeetCode problem | Workspace: statement, starter code, examples as test input | ✅ / ⬜ |
| 4.2 | Without extension or app | Banner says how to get them; Run/Submit disabled | ✅ / – |
| 4.3 | Extension 0.3.0 still installed | "Extension too old" banner | ⬜ / – |
| 4.4 | *Sign in to LeetCode* | Small LeetCode sign-in window; closes itself after you sign in (you tick the human check yourself) | ⬜ / ⬜ |
| 4.5 | **Run** with correct code | Each case's output shown, matching expected | ⬜ / ⬜ |
| 4.6 | **Run** with wrong code, a compile error, a runtime error | Wrong cases in red; compiler/runtime message shown | ⬜ / ⬜ |
| 4.7 | **Submit** correct code | "Accepted", tests count, runtime/memory; toast "counted for the lab"; Labs page shows ✓ | ⬜ / ⬜ |
| 4.8 | **Submit** wrong code / time limit | Verdict and failing input shown; not counted | ⬜ / ⬜ |
| 4.9 | Signed in to LeetCode as a different account than the linked one | Warning that solves won't count | ✅ (test) / ⬜ |
| 4.10 | Switch language, type, switch back, reload | Each language keeps its own draft | ✅ (test) / ⬜ |
| 4.11 | Submit several times quickly | LeetCode's "too often" message shown cleanly | ⬜ / ⬜ |
| 4.12 | LeetCode session expired / signed out on leetcode.com | Asks you to sign in again | ⬜ / ⬜ |
| 4.13 | Premium problem | Statement says to read it on LeetCode | ⬜ / ⬜ |
| 4.14 | Desktop app during an **examination lockdown** | LeetCode sign-in and submit refused | – / ⬜ |
| 4.15 | If 4.5 or 4.7 fail: note the exact message. Likeliest cause is LeetCode's CSRF check on Run/Submit | – | ⬜ |

## 5. CSES

| # | Steps | Expected | Status |
|---|---|---|---|
| 5.1 | Platforms → *Connect CSES* | cses.fi "CSES CLI authorization" opens; card says it's waiting | ✅ |
| 5.2 | Sign in on cses.fi to approve | Within a few seconds the card shows your CSES username, "Connected" | ⬜ |
| 5.3 | Don't approve for 15 minutes | Card says it wasn't approved in time | ⬜ |
| 5.4 | Connect the same CSES account from a second CPIntel account | Refused | ⬜ |
| 5.5 | *Disconnect* | Account removed; CSES lab solves stop counting | ⬜ |
| 5.6 | Sign out of the CPIntel token on cses.fi (or wait for it to expire), then let a check run | Card shows "Reconnect CSES"; Labs page warns | ⬜ |
| 5.7 | Labs page → *Solve* on a CSES task | Statement with rendered maths, limits, example | ✅ |
| 5.8 | Open several CSES tasks, including ones with display maths or pictures | All render properly | ⬜ |
| 5.9 | **Run** on the example (C++, Python) | Passes/fails shown; first C++ run downloads the compiler (slow once) | ✅ (Python) / ⬜ (C++, C, Java) |
| 5.10 | **Submit in C++** | Judged on CSES; progress shown; "Accepted" counts for the lab at once | ⬜ |
| 5.11 | **Submit in C, Java, Python 3** | Accepted by CSES. If refused, note the message: language names are a guess | ⬜ |
| 5.12 | Submit wrong code / compile error | Verdict, first failing test, compiler output | ⬜ |
| 5.13 | Two submits within 10 seconds | Second asks you to wait | ⬜ |
| 5.14 | Solve a CSES lab task **on cses.fi directly** | Counted within 10 min | ⬜ |
| 5.15 | Compare a CSES solve's time in the grid/export with cses.fi | Same time (if it's 2–3 hours off, the time-zone guess is wrong) | ⬜ |
| 5.16 | CSES task solved before the lab opened, toggle off / on | Off: not counted. On: counted | ⬜ |
| 5.17 | Server without `CPINTEL_SESSION_KEY` | *Connect CSES* gives a clear "not set up" message | ⬜ |

## 6. Codeforces problems from the Labs page

| # | Steps | Expected | Status |
|---|---|---|---|
| 6.1 | *Solve* on a Codeforces lab problem | Opens that problem in Practice | ✅ (link) / ⬜ (submit from Practice) |
| 6.2 | Gym problem | No *Solve* button (solved on Codeforces) | ✅ |

## 7. Student Labs page and general UI

| # | Steps | Expected | Status |
|---|---|---|---|
| 7.1 | Labs in the sidebar; Open and Past sections; marks and progress bar | Correct | ✅ |
| 7.2 | Missing/unverified/disconnected account banner names exactly what to fix | Correct per platform | ✅ |
| 7.3 | Phone width (≈390 px): Labs, workspaces, admin grid | No sideways scrolling of the page; grid scrolls inside its panel | ✅ (Labs) / ⬜ (workspaces, grid) |
| 7.4 | Light and dark theme on every new page | Readable | ✅ (some) / ⬜ (rest) |
| 7.5 | Keyboard only: tab through the editor page, Run/Submit, verify flow | Focus visible, everything reachable | ⬜ |
| 7.6 | Statement images (LeetCode/CSES) on the **production** server | Images load (CSP allows https images) | ⬜ |

## 8. Deployment (college server)

| # | Steps | Expected | Status |
|---|---|---|---|
| 8.1 | Deploy; check the backend log | Migrations **V34** and **V35** applied | ⬜ |
| 8.2 | Server can reach `leetcode.com/graphql` and `cses.fi/api` | No timeouts in the log (campus network/proxy) | ⬜ |
| 8.3 | Existing Codeforces analytics, nightly sync, exams unaffected | Same as before | ⬜ |
| 8.4 | Students re-download the extension from `/extension` on the server | 0.4.0 installs; Codeforces still works with it | ⬜ |
| 8.5 | New desktop installers built and installed | LeetCode sign-in available in the app | ⬜ |

---

## Known guesses to watch

- **CSES language names**: C++ is sent as "C++" / "C++17" (from CSES's own client docs). C, Java and Python are left for CSES to detect from the file name. See 5.11.
- **CSES submission times** with no zone are read as Helsinki time. See 5.15.
- **LeetCode Run/Submit** depend on LeetCode's unofficial site API and its CSRF check. See 4.15.
- **CSES API** says it has "no stability guarantees".
