# Examination load test

An examination room of 25 to 200 candidates played against the production stack, marked by a
real DOMjudge, all on this machine. It answers one question before exam day: does a room of 200
opening a paper together, running samples, submitting and refreshing get its answers in time,
and if not, which part gives way first?

| File | What it does |
|---|---|
| `stack.sh up \| status \| down` | The production compose stack under its own project (`cpintel-loadtest`) with the target server's memory limits, plus DOMjudge 9.0.0 (database, domserver, three judgehosts). Random keys in `out/loadtest.env`. |
| `docker-compose.loadtest.yml` | The pinning, the limits, `cpintel-lt-*` names, the judge; nothing published but nginx and the judge on 127.0.0.1:12345. |
| `setup_judge.py` | Loads the judge: contest `loadtest`, the six problems, 200 teams and their accounts; checks a team can sign in, C++ is on and the judgehosts registered. Writes `out/judge.json`. |
| `provision_exam.py` | Classroom on the judge, team, roster with DOMjudge logins, the examination, its problems, room password and candidate passwords, into `out/exam.json`. Re-runnable: it reuses what exists. |
| `exam_sim.py` | The room. One thread per candidate at the exam page's own cadence; see its `--help`. |

None of it touches a development stack: its own project, volumes, container names and subnet
(172.29.0.0/16). `stack.sh down` removes its volumes and nothing else.

**The cores.** CPIntel on 4–7 (`LOADTEST_CPUS`), as the four-core server it will have; the
judge's database and domserver on 3 and 8 (`JUDGE_CPUS`), a judgehost on each of 9, 10 and 11;
the simulator on 0–2 under `taskset`. The domserver needs the second core: on one efficiency core
it saturated at 100 candidates (every test case of every judging is a PHP request) and held the
judging back while the judgehosts idled. On exam day the judge is a machine of its own, so it is kept
off CPIntel's cores here too.

## 1. Up

The problems and people come from `scripts/simulation` (its README has the details); both are
already generated in `scripts/simulation/out`, otherwise:

```bash
./scripts/simulation/make_problemset.py --verify     # out/problemset/*.zip
./scripts/simulation/make_roster.py --count 200      # out/roster/
```

Close what you can first: the stack's limits add up to more than this machine's memory, though
it uses far less than its limits.

```bash
./scripts/loadtest/stack.sh up            # builds; about 5 minutes the first time
./scripts/loadtest/setup_judge.py
./scripts/loadtest/provision_exam.py --insecure --users 200 --starts-in 10 --duration 120
```

The judge's contest opens at once and runs for 12 hours (`setup_judge.py --hours`), so it
covers every examination provisioned in that time. Its jury pages are at
<http://127.0.0.1:12345> (admin, password in `out/judge.json`). For a judge on another machine
instead: `LOADTEST_JUDGE=remote stack.sh up`, load it by hand as `scripts/simulation/README.md`
describes (teams.tsv **and** accounts.tsv, since each candidate submits as their own login), and
give `provision_exam.py` its `--domjudge-url`, `--dj-service-user`, `--dj-service-password` and
`--dj-contest`.

## 2. The run

Then within the hour before the bell (sign-in by exam code opens an hour early):

```bash
taskset -c 0-2 ./scripts/loadtest/exam_sim.py --insecure --users 25 --duration 15 --out scripts/loadtest/out/report-25.txt
```

`exam_sim.py` waits for the bell, so start it a few minutes before. Give each step of the ramp
an examination of its own, so each has a real bell (the burst that matters most):

```bash
./scripts/loadtest/provision_exam.py --insecure --starts-in 4 --duration 20 --name "Load test 50"
taskset -c 0-2 ./scripts/loadtest/exam_sim.py --insecure --users 50 --duration 12 \
    --sign-in-spread 120 --out scripts/loadtest/out/report-50.txt
```

Run nothing else heavy meanwhile (a Maven build on the same machine is enough to move the
numbers). Repeat sign-ins across examinations show in their suspicious-activity lists; that is
the simulation, not a finding.

Watch while it runs: Grafana at <http://127.0.0.1:3000> (admin / `GRAFANA_PASSWORD` in
`out/loadtest.env`), alerts at <http://127.0.0.1:9090/alerts>, and `docker stats`.

```bash
./scripts/loadtest/stack.sh down                     # removes the stack and its volumes
```

## 3. Reading it

The report is `simulate.py`'s: latency per endpoint (p50/p90/p99/max), every non-2xx status,
verdicts, and submit → verdict times. In order:

1. **Statuses.** 429s are a rate limit (every candidate comes from one address, as a lab behind
   one NAT does; `CPINTEL_LAB_NETWORKS` declares it, so these are the limits a real room meets).
   503s are nginx or a saturated pool, 500s a bug. Any 4xx on submit or unlock is a headline.
2. **The bell.** `POST .../unlock` and the first minute of `.../run`: the whole room at once.
3. **submit → verdict.** What candidates feel. If it climbs with the room while CPIntel's own
   endpoints stay flat, the judge is the bottleneck, not CPIntel.
4. **p99 against p50** on reads. A flat p50 with a climbing p99 is queueing: Tomcat threads, the
   Hikari pool, or the runner's slots.
5. **Grafana.** Server CPU busy (the four pinned cores), JVM heap and threads, active DB
   connections, P99 latency, and 429s and 5xx over time, to line the report up against.

A number means something next to the same number at another size, so keep all four reports.

## What this machine is and isn't

Intel Core 5 120U: CPUs 0–3 are two hyper-threaded performance cores, 4–11 eight efficiency
cores. CPIntel gets four efficiency cores, slower than a server's, so a result that passes here
passes there with room to spare; a failure here is worth re-checking on the real server before
acting on it. Three judgehosts on efficiency cores are a small judge, so expect submit → verdict to
be the judge's queue at 200: read it against the judge's own queue in its jury pages, not as
CPIntel's latency.
