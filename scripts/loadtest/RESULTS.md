# Load test results

## 21 Nov 2026: 400 candidates

Same laptop and pinning as below, a 30-minute paper, 400 candidates signing in over five minutes,
on the code of that day (refresh-token claim, problem-list back-off, audit pool included).

**First run: the room could not get in.** At the bell 933 requests got no reply, 249 `enter` and
328 `unlock` calls among them, and 200 requests failed together after exactly the pool's 30 s
timeout. A thread dump taken during the stall showed all 200 request threads waiting for a
database connection and the 20 pooled connections held by threads of the same requests:
`EventService.unlock` is transactional (one connection) and wrote its audit entry through
`AuditService.recordIn`, a `REQUIRES_NEW` transaction (a second). Twenty unlocks each held one and
waited for another, so nothing moved until the timeout. The 200-candidate run did not stall
(unlock p99 67 ms): at that size the arrivals did not line twenty unlocks up at once.

**Fix:** audit rows are written over a small pool of their own (`AuditWriter`, four connections,
5 s wait, each insert committed on its own, as before), so no request waits on a connection that
only requests hold. A test fails if any `AuditService` method becomes transactional again.

**Second run, same room:** every request returned 2xx (132,863 requests, 65/s). The bell is
still slower than at 200 (unlock p50 3.2 s against 32 ms): four hundred requests queue for twenty
connections at the bell (not examined further).

| | p50 | p90 | p99 |
|---|---|---|---|
| `POST /exams/{id}/unlock` (the bell) | 3.2 s | 5.2 s | 6.5 s |
| `POST /exams/{id}/enter` | 2.5 s | 5.0 s | 6.3 s |
| `POST /auth/login` (exam code) | 51 ms | 64 ms | 113 ms |
| `POST /compete/.../submit` | 0.20 s | 0.35 s | 0.80 s |
| `GET /exams/{id}` (the page poll) | 17 ms | 169 ms | 10.8 s |
| `POST /exams/{id}/monitor/heartbeat` | 8 ms | 92 ms | 4.8 s |

CPIntel's backend peaked at 171% of its four cores and 1.5 GB; nginx 15%.

**What did give way, and is not CPIntel's:**
- **The code runner.** `POST /run` p50 1.3 s but p90 52 s and 116 Runs timed out at the page's 120 s
  wait; the runner sat at 302% of its cores. Each candidate ran about 3.4 times in the paper. On
  the server give the runner its own cores and expect the same cliff somewhere between 200 and
  400 candidates running at this rate.
- **The judge.** submit → verdict p50 358 s, p90 377 s (200 candidates: p50 16 s, p90 30 s): 1,008
  submissions in 25 minutes onto three efficiency-core judgehosts and a domserver at 153%. CPIntel's
  own endpoints stayed flat while it climbed, so this is the judge's queue; the college's judge
  needs sizing for the room.
- The long tails (p99 of 5 to 11 s on the page poll and heartbeat) lie at the bell and while the
  runner was starved; they are not steady-state.

One `AsyncRequestNotUsableException` (a client dropped the connection mid-response) is logged at
ERROR; it is noise, not a failure.

## 5 Nov 2026: 200 candidates again, on this round's code

Same machine and layout as 16 October (CPIntel on four efficiency cores, 4–7, with the production
memory limits; DOMjudge 9.0.0 on its own cores; the simulator on 0–2), built from the working tree
with this round's changes (Codeforces exams, CPIntel-created DOMjudge contests, the question bank
switches). A 25-candidate warm-up, then 200 candidates on a 30-minute paper, 15 minutes of it
simulated after the bell, 120 s sign-in spread.

**Every one of 26,059 requests returned 2xx; no backend ERROR lines; no alert fired or pending.**

| | 16 Oct, 200 | 5 Nov, 200 |
|---|---|---|
| heartbeat p99 | 83 ms | 29 ms |
| paper (GET exam) p99 | 131 ms | 29 ms |
| unlock at the bell p99 | 89 ms | 67 ms |
| exam-code sign-in p99 | 66 ms | 40 ms |
| submissions list p50 / p90 | — / 1.2 s | 60 ms / 497 ms |
| Run p50 / p90 / max | 1.1 / 26.7 / 56 s | 0.67 / 13.1 / 24.2 s |
| submit → verdict p50 / p90 | 119 s ‡ | 15.9 / 30.3 s |

Resources over the 200 run (sampled every 15 s): all of CPIntel together peaked at 340% of the
four cores (p50 45%, p90 157%) and 1.8 GB of memory. Backend 966 MB of 2.5 GB, runner 344 MB of
2 GB (it is the only part that uses the cores in earnest, up to three at the bell's Run burst),
MongoDB 236 MB, PostgreSQL 96 MB, Redis 5 MB. No zombies in the runner.

Run is still the one number that grows with the room (three runner slots), but every Run
finished: nginx logged 200 for all of them, none 503 "busy".

**Two simulator faults, found by the first 200 run and fixed in `exam_sim.py`:**
- It gave Run the 30 s every other request gets, so 21 runs taking 30–41 s were counted as
  "no reply" (nginx 499: the simulator hung up; the server finished them). The examination page
  waits 120 s for Run; so does the simulator now.
- A candidate whose last Run ended after the paper's simulated end still submitted, after its
  heartbeat had stopped; CPIntel refused 14 of those as "nothing is reporting from your machine",
  which is the monitoring working. Candidates now start nothing in the last seconds of their time.

The first run's report is kept as `out/report-200-sim-30s-timeout.txt`.

# 16 Oct 2026

One laptop (Intel Core 5 120U, 14.5 GB): CPIntel on four efficiency cores (4–7) with the
production limits, DOMjudge 9.0.0 on 3, 8 (database and domserver) and 9–11 (three judgehosts),
the simulator on 0–2. Each step was its own examination with a real bell; a 12–15 minute paper
after it. Reports and samples are in `out/` (not committed).

## What it found, and what was fixed

| # | Finding | Severity | Fix |
|---|---|---|---|
| 1 | **The runner leaked zombie processes.** Runs left orphaned `bwrap` processes behind (975 by the time it failed), and the JVM, as PID 1, never reaped them; at 1024 (the container's pids limit) every Run, and every new runner thread, failed with EAGAIN until the runner was restarted. Reached 40 minutes into the test. Run still answered 200, with the failure inside. | Exam-stopping | `init: true` on the runner (docker-init as PID 1). 200 candidates afterwards: runner peaked at 73 pids, no zombies, no "Runner unreachable". Root cause found afterwards, in bubblewrap rather than RunEngine: its outer process exits once it has the exit code, before its inner one (PID 1 of the sandbox) exits, so every sandbox (each compile and each test) orphans one process to the container's PID 1. The image now starts under tini as well. |
| 2 | **The standings scheduler rolled back every live board** whenever a team-assigned event (every classroom exam or contest, which has no group) was live: NullPointerException in one contest marked the shared transaction rollback-only, and it recurred every 10 minutes (ShedLock's ceiling). | High | Team-assigned events are skipped (their boards come from the submission archive); one transaction per contest. Tested. |
| 3 | **No samples on DOMjudge 9.** Its API has no per-problem test cases; samples come in one `contests/{cid}/samples.zip`, a folder per label. Run had nothing to run. | High (on DOMjudge 9) | New route `API_CONTEST_SAMPLES_ZIP`; one download caches the whole contest. Tested. |
| 4 | **Run was compile-bound**: `<bits/stdc++.h>` took 2.6 s of a run. | High at a bell | Precompiled header in the runner image, beside the header, built with the runner's flags: 0.8 s. A flag mismatch makes g++ ignore it, so it cannot change a result. |
| 5 | **The browser gave up on Run at 30 s** (the client default) while the backend waits up to 20 s queue + 20 s compile + 6 s per test. Under load, runs took up to 56 s. | Medium | Run has its own 120 s timeout, matching nginx's. |
| 6 | **The backend crash-looped on a blank `JWT_EXPIRY_MS` / `JWT_REFRESH_EXPIRY_MS`**: compose passed them with no default and `.env.example` leaves them blank. | Deploy-stopping | Defaults of 15 min and 7 days in compose. |
| 7 | **The frontend container was always "unhealthy"**: its healthcheck asked `localhost`, which is IPv6 inside it, and nginx listens on IPv4. | Low | Checks 127.0.0.1. |

## Numbers (after the fixes that were in by then)

CPIntel's own endpoints stayed flat all the way to 200, every request 2xx:

| | 25 | 50 | 100 | 200 |
|---|---|---|---|---|
| heartbeat p99 | 64 ms | 32 ms | 21 ms | 83 ms |
| paper (GET exam) p99 | 75 ms | 29 ms | 22 ms | 131 ms |
| unlock at the bell p99 | 158 ms | 107 ms | 58 ms | 89 ms |
| exam-code sign-in p99 | 83 ms | 64 ms | 57 ms | 66 ms |
| Run p50 / p90 / max | 0.96 / 3.4 / 6.4 s | 0.97 / 6.1 / 11.7 s | † | 1.1 / 26.7 / 56 s |
| submit → verdict p50 | 10.4 s | 10.5 s | 10.7 s | 119 s ‡ |

† The 100 runs fell after the runner had run out of pids (finding 1); their Run numbers are
failures and are not shown. ‡ The judge's own queue (below), not CPIntel.

**Run at 200** is the one CPIntel number that moves: three slots, and a room's mistakes hold
them — a TLE solution keeps its slot for the 5 s limit on every sample. Queueing is bounded at
20 s, then 503 "busy"; none were refused at 200, 7 of 746 outlasted 30 s (now within the
browser's timeout). More slots need more cores than the target server's four.

**The judge, on this laptop, was the bottleneck at 200.** Submissions waited about 107 s for a
judgehost: three efficiency-core judgehosts judge about 0.65 submissions a second, and the
simulated room sent 0.85 (765 in 15 minutes). On one core the domserver itself saturated at
100 (every test case of every judging is a PHP request); it needs two. A real paper is gentler
— about 12 submissions per candidate over three hours is a quarter of this rate — but size the
judge for the room: judgehosts at one submission per ~4.6 s each on this class of core.

## Not fixed, worth knowing

- ~~A refresh of the shared contest cache runs on whichever request finds it stale, so a slow
  judge shows in CPIntel's own `GET .../submissions` (p90 0.6 s at 100, 1.2 s at 200).~~ Now
  refreshed in the background (eight at a time); every reader gets the stale copy meanwhile.
  Not re-measured under load.
- ~~A Run that fails inside the runner is logged at WARN only; nothing alerts on it.~~ Now
  counted (`cpintel_run_failed_total` by reason) and alerted on (`RunnerFailing`, `RunnerBusy`).
- DOMjudge 8.x judgehosts need cgroup v1; on a cgroup-v2-only host (current Ubuntu), use 9.x.
- The judgehosts compile `<bits/stdc++.h>` from source too; the same precompiled header in the
  judgehost chroot would cut judging time the same way.
