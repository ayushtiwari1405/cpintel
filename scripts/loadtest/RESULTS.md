# Load test results, 16 Oct 2026

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
