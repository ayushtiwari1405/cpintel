# Contest simulation

Everything needed to run a fake 200-person contest against CPIntel and DOMjudge: the problems,
the participants, and a driver that plays all 200 of them at once and reports what the server
did.

Nothing here needs a package installed. Python 3.10+ and, for `--verify`, `g++`.

## The short version

```bash
cd /path/to/cpintel

# 0. Prove the harness works before trusting it against anything real.
./scripts/simulation/selftest.py

# 1. Build the problems and the roster.
./scripts/simulation/make_problemset.py --verify
./scripts/simulation/make_roster.py --count 200

# 2. Load them into DOMjudge (see below), then into CPIntel:
./scripts/simulation/provision.py \
    --api http://localhost:8080/api/v1 \
    --admin-email you@example.com --admin-password '...' \
    --roster scripts/simulation/out/roster/roster.json \
    --contest-id 3 --contest-name "Simulation Round" \
    --starts-in 2 --duration 180

# 3. Run the contest.
./scripts/simulation/simulate.py \
    --api http://localhost:8080/api/v1 \
    --roster scripts/simulation/out/roster/roster.json \
    --contest 3 --users 200 --duration 20
```

Everything generated lands in `scripts/simulation/out/` and is gitignored.

## What each piece does

| Script | What it produces |
|---|---|
| `make_problemset.py` | Six problems as DOMjudge-importable zips, plus their solutions on disk |
| `make_roster.py` | 200 participants: CPIntel accounts and the matching DOMjudge teams |
| `provision.py` | Creates the accounts, group, memberships and contest inside CPIntel |
| `simulate.py` | Plays N contestants at once and reports latency, errors and verdicts |
| `stub_server.py` | A fake CPIntel, so the harness can be tested with nothing else running |
| `selftest.py` | Runs the simulator against the stub and checks the result is sane |

### The problems

Six, A–F, from "add two numbers" to a BFS on a grid. Each ships an accepted solution and
several deliberately wrong ones — a wrong answer, a quadratic solution that times out, a
missing semicolon that will not compile, a division by zero. The simulator submits a mix, which
is the point: a load test that only ever submits correct code exercises one path through the
verdict pipeline and tells you nothing about what the arena does with a compile error.

Test data is generated from a seed, so the package is reproducible. Answers come from a Python
reference implementation of each problem, and `--verify` compiles the C++ solutions and checks
that every one of them draws the verdict it claims:

```
  C: accepted agrees with the reference on 6 tests
    quadratic            TLE  - still running after 6s on secret 4
    clamps-at-zero       WA   - wrong on sample 2
```

That check earns its keep. It caught one of the "wrong" solutions in this very set being
accidentally correct, which would have had the simulator waiting for a verdict that never came.

### The participants

`make_roster.py` writes **one** roster and derives both systems from it, because the failure
mode otherwise is a contest where forty people are silently unmatched. A CPIntel account
competes as a DOMjudge team by having its group membership's `externalHandle` equal that team's
name; `sim001` maps to `Team 001`, and so on.

Passwords are derived from a seed rather than random, so re-running the generator reproduces
the same roster instead of orphaning the 200 accounts you already created.

## Loading DOMjudge

1. **Teams.** Jury → Import/Export → import `out/roster/domjudge/teams.tsv`.
   `out/roster/domjudge/teams.json` is the same list in the shape DOMjudge 8 takes over its API.

   Contestants do **not** need DOMjudge logins — CPIntel submits on each team's behalf through
   the deployment's admin API account, so the teams alone are enough to attribute submissions
   and fill the scoreboard. `accounts.tsv` is generated anyway, in case you also want people
   able to open DOMjudge's own UI, or in case your build requires a team to have a user before
   it will accept a submission on its behalf.

2. **Problems.** Jury → Problems → Import problem, one zip at a time, or:

   ```bash
   cd scripts/simulation/out/problemset
   for z in *.zip; do
     curl -u admin:PASS -F zip=@"$z" "$DJ_URL/api/v4/contests/$CID/problems"
   done
   ```

3. **Check it took.** `./scripts/domjudge-probe.sh` will now report the problems, the teams and
   which sample-testcase route your build exposes.

## Reading the report

```
endpoint                                n      p50      p90      p99      max
------------------------------------------------------------------------------
GET .../submissions                  4821      12m      31m     140m     892m
POST .../submit                       310      45m      92m     310m    1204m
```

Three things to look at, in order:

1. **Status section.** Anything other than "every request returned 2xx" is the headline. A wave
   of 429s is rate limiting; 503s are nginx or a saturated thread pool; 500s are a real bug.
2. **`submit → verdict` timing.** This is the number contestants actually feel. If p90 climbs
   as you add users, the judge — not CPIntel — is the bottleneck.
3. **p99 versus p50 on the read endpoints.** A p50 that stays flat while p99 climbs is queueing,
   usually Tomcat threads or the Hikari pool rather than anything in the arena.

Ramp up rather than starting at 200: run 25, then 50, then 200, and compare. A number is only
meaningful next to the same number at a different load.

## Two things that will bite

**Sign-in is rate limited per address.** CPIntel allows 20 sign-ins a minute *per IP*, and all
200 simulated contestants come from one machine. The simulator backs off and retries, and tells
you at the end how often it was throttled — but for a clean run, either use a long `--ramp` or
turn the limiter off for the test:

```bash
CPINTEL_RATE_LIMIT_ENABLED=false
```

This is worth thinking about for the real contest too, not only the simulation. A room of 200
people behind one campus NAT looks exactly like this script does. The same goes for nginx's
`auth` zone at 5r/s.

**The contest window has to match DOMjudge's.** CPIntel accepts monitoring reports only inside
the window plus a short grace, and only refreshes standings for contests it considers live. A
window that disagrees with the judge's produces a contest that looks fine and quietly records
nothing. `provision.py --starts-in` and `--duration` set it; the DOMjudge side is set in the
jury interface.

## Useful flags

```
simulate.py --read-only     never submit — pure read scaling, no load on the judge
            --users 25      how many contestants
            --duration 20   minutes
            --ramp 120      seconds to bring everyone online
            --think 0.2     five times busier than a real contestant, to force the issue
            --out r.txt     also write the report to a file
```

`--read-only` is the one to reach for first: it separates "can CPIntel serve 200 people
reading" from "can the judge judge 200 people submitting", and those fail for entirely
different reasons.
