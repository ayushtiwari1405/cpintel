#!/usr/bin/env python3
"""
Drives N simulated contestants through a live CPIntel contest and reports what the server did.

    ./scripts/simulation/simulate.py \\
        --api http://localhost:8080/api/v1 \\
        --roster scripts/simulation/out/roster/roster.json \\
        --contest nwerc18 --users 200 --duration 20

Each contestant is a thread doing what the real page does, at the cadence the real page does
it: read the contest, open statements, poll submissions every 5s while something is judging and
every 60s when nothing is, re-read the rank, submit, and — if the round is monitored — post
focus-loss reports every 15 seconds. The point is to reproduce the *shape* of the load, not
just its volume: a flood of identical requests would miss that the expensive polling only
starts once people begin submitting.

What it measures, per endpoint: p50/p90/p99/max latency, throughput, and every non-2xx broken
out by status. What it also measures, and what the whole DOMjudge design rests on: whether
verdicts actually come back, and how long they take from submit to resolved.

Useful modes:

    --read-only     never submit; measures pure read scaling without loading the judge
    --users 25      ramp up gradually — 25, then 50, then 200 — rather than starting at the top
    --ramp 120      seconds to bring everyone online, which also spreads the login burst

**On rate limits.** CPIntel throttles sign-in per *address* (20/minute by default), and every
simulated contestant comes from this one machine, so a 200-user run will be throttled at the
door unless you either ramp slowly or turn the limiter off for the test:

    CPINTEL_RATE_LIMIT_ENABLED=false

That throttle is worth thinking about for the real contest too, not just the simulation — a
room of 200 people behind one NAT looks exactly like this script does.
"""

from __future__ import annotations

import argparse
import json
import random
import sys
import threading
import time
import uuid
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from simclient import Client, Response   # noqa: E402

STOP = threading.Event()


# ---------------------------------------------------------------- measurement

class Metrics:
    """Every request's outcome, aggregated at the end."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self.latencies: dict[str, list[float]] = defaultdict(list)
        self.statuses: dict[str, Counter] = defaultdict(Counter)
        self.verdicts: Counter = Counter()
        self.verdict_delays: list[float] = []
        self.errors: list[str] = []
        self.throttled_logins = 0
        self.transport_failures = 0
        self.started_at = time.time()

    def record(self, label: str, response: Response) -> None:
        with self._lock:
            self.latencies[label].append(response.elapsed)
            self.statuses[label][response.status] += 1

    def note_error(self, message: str) -> None:
        with self._lock:
            # Capped: a total outage would otherwise produce a million identical lines and
            # the report would be unreadable at exactly the moment it matters.
            if len(self.errors) < 200:
                self.errors.append(message)

    def note_verdict(self, verdict: str, delay: float = None) -> None:
        with self._lock:
            self.verdicts[verdict] += 1
            if delay is not None:
                self.verdict_delays.append(delay)

    def note_throttle(self) -> None:
        with self._lock:
            self.throttled_logins += 1

    def record_transport(self, label: str) -> None:
        """
        A request that never got a reply at all.

        Counted under status 0 rather than dropped, so a connection-refused storm shows up in
        the report as a wall of failures instead of as a suspiciously low request count.
        """
        with self._lock:
            self.statuses[label][0] += 1
            self.transport_failures += 1

    # ------------------------------------------------------------- reporting

    @staticmethod
    def _pct(values: list[float], p: float) -> float:
        if not values:
            return 0.0
        ordered = sorted(values)
        k = min(len(ordered) - 1, int(round((p / 100) * (len(ordered) - 1))))
        return ordered[k]

    def report(self, elapsed: float) -> str:
        lines = []
        add = lines.append

        add("")
        add("=" * 78)
        add("REQUEST LATENCY")
        add("=" * 78)
        add(f"{'endpoint':<34}{'n':>7}{'p50':>9}{'p90':>9}{'p99':>9}{'max':>9}")
        add("-" * 78)

        total_requests = 0
        for label in sorted(self.latencies, key=lambda k: -len(self.latencies[k])):
            values = self.latencies[label]
            total_requests += len(values)
            add(f"{label:<34}{len(values):>7}"
                f"{self._pct(values, 50) * 1000:>8.0f}m"
                f"{self._pct(values, 90) * 1000:>8.0f}m"
                f"{self._pct(values, 99) * 1000:>8.0f}m"
                f"{max(values) * 1000:>8.0f}m")

        add("-" * 78)
        add(f"{'TOTAL':<34}{total_requests:>7}"
            f"{'':>9}{'':>9}{'':>9}{elapsed:>7.0f}s")
        add(f"throughput: {total_requests / max(elapsed, 1):.1f} requests/second")

        add("")
        add("=" * 78)
        add("RESPONSE STATUS")
        add("=" * 78)
        clean = True
        for label in sorted(self.statuses):
            counts = self.statuses[label]
            bad = {s: c for s, c in counts.items() if s >= 400 or s == 0}
            ok = sum(c for s, c in counts.items() if 0 < s < 400)
            if bad:
                clean = False
                detail = "  ".join(f"{s}×{c}" for s, c in sorted(bad.items()))
                add(f"  {label:<34}{ok:>7} ok    {detail}")
        if clean:
            add("  every request returned 2xx")
        if self.transport_failures:
            add("")
            add(f"  {self.transport_failures} requests got no reply at all (shown as status 0):")
            add("  connection refused, reset, or dropped twice. The server was unreachable,")
            add("  not merely slow.")

        if self.throttled_logins:
            add("")
            add(f"  {self.throttled_logins} sign-ins were rate-limited (429) and retried.")
            add("  CPIntel limits sign-in per address and every contestant here shares one.")
            add("  Use a longer --ramp, or CPINTEL_RATE_LIMIT_ENABLED=false for the test.")

        add("")
        add("=" * 78)
        add("VERDICTS")
        add("=" * 78)
        if self.verdicts:
            total = sum(self.verdicts.values())
            for verdict, count in self.verdicts.most_common():
                add(f"  {verdict:<28}{count:>6}  {count / total * 100:>5.1f}%")
            if self.verdict_delays:
                add("")
                add(f"  submit → verdict, over {len(self.verdict_delays)} resolved "
                    f"submissions:")
                add(f"    p50 {self._pct(self.verdict_delays, 50):>6.1f}s   "
                    f"p90 {self._pct(self.verdict_delays, 90):>6.1f}s   "
                    f"max {max(self.verdict_delays):>6.1f}s")
        else:
            add("  no submissions were made (read-only run, or none resolved)")

        if self.errors:
            add("")
            add("=" * 78)
            add(f"ERRORS  (first {min(len(self.errors), 15)} of {len(self.errors)})")
            add("=" * 78)
            for message in self.errors[:15]:
                add(f"  {message}")

        add("")
        return "\n".join(lines)


# ------------------------------------------------------------------ behaviour

class Contestant:
    """
    One simulated person.

    Behaviour is driven by a per-contestant ``skill`` drawn once: it decides how many problems
    they attempt, how long they think before submitting, and how often their first attempt at a
    problem is wrong. Giving everyone identical behaviour would produce a load pattern that
    never happens — real contests have a handful of people submitting constantly and a long
    tail who submit twice in three hours.
    """

    def __init__(self, entry: dict, args, metrics: Metrics, solutions: dict, rng: random.Random):
        self.entry = entry
        self.args = args
        self.metrics = metrics
        self.solutions = solutions
        self.rng = rng

        self.client = Client(args.api, timeout=args.timeout, verify_tls=not args.insecure)
        self.token: str = None
        self.refresh_token: str = None

        self.skill = rng.random()
        #: Stronger contestants attempt more of the set.
        self.attempts = max(1, min(len(solutions), int(1 + self.skill * len(solutions))))
        self.language_id: str = None
        self.group_contest_id: int = None
        self.pending: dict[str, float] = {}    # submission id -> when it was sent
        self.seen_verdicts: set[str] = set()

    # ------------------------------------------------------------------- http

    def call(self, label: str, method: str, path: str, **kw) -> Response:
        try:
            response = self.client.request(method, path, token=self.token, **kw)
        except OSError as e:
            # The server refused the connection, or dropped it twice running. That is a
            # result, not a reason to lose this contestant: recorded as a transport failure
            # and reported, because an outage that silently removes clients would make the
            # report understate exactly the problem it exists to find.
            self.metrics.record_transport(label)
            self.metrics.note_error(f"{self.entry['username']} {label} → {type(e).__name__}: {e}")
            self.client.close()
            return Response(status=0, body=b"", elapsed=0.0)

        self.metrics.record(label, response)

        if response.status == 401 and self.token:
            # The access token lives 15 minutes by default and a contest runs for hours, so
            # this is an expected event rather than a failure — every contestant will hit it.
            if self.reauth():
                response = self.client.request(method, path, token=self.token, **kw)
                self.metrics.record(label, response)

        if not response.ok and response.status not in (401, 403):
            self.metrics.note_error(
                f"{self.entry['username']} {label} → {response.status} {response.message()}")
        return response

    def login(self) -> bool:
        """Signs in, backing off through the per-address rate limit if it bites."""
        for attempt in range(6):
            try:
                response = self.client.post("/auth/login", body={
                    "email": self.entry["email"],
                    "password": self.entry["password"],
                })
            except OSError as e:
                self.metrics.record_transport("POST /auth/login")
                self.metrics.note_error(
                    f"{self.entry['username']} login → {type(e).__name__}: {e}")
                self.client.close()
                time.sleep(1 + self.rng.random())
                continue
            self.metrics.record("POST /auth/login", response)

            if response.ok:
                data = response.data()
                self.token = data["accessToken"]
                self.refresh_token = data.get("refreshToken")
                return True

            if response.status == 429:
                self.metrics.note_throttle()
                # Exponential with jitter: without the jitter every throttled contestant
                # retries in lockstep and re-creates the burst that got them throttled.
                time.sleep(min(60, 2 ** attempt) * (0.5 + self.rng.random()))
                continue

            self.metrics.note_error(
                f"{self.entry['username']} login → {response.status} {response.message()}")
            return False

        self.metrics.note_error(f"{self.entry['username']} gave up signing in after 6 tries")
        return False

    def reauth(self) -> bool:
        if self.refresh_token:
            response = self.client.post("/auth/refresh",
                                        body={"refreshToken": self.refresh_token})
            self.metrics.record("POST /auth/refresh", response)
            if response.ok:
                data = response.data()
                self.token = data["accessToken"]
                self.refresh_token = data.get("refreshToken", self.refresh_token)
                return True
        return self.login()

    # -------------------------------------------------------------- the round

    def base(self) -> str:
        return f"/compete/DOMJUDGE/{self.args.contest}"

    def open_contest(self) -> dict:
        response = self.call("GET /compete/{p}/{c}", "GET", self.base())
        if not response.ok:
            return None
        return response.data()

    def load_languages(self) -> None:
        response = self.call("GET .../languages", "GET", f"{self.base()}/languages")
        if not response.ok:
            return
        options = response.data() or []
        if not options:
            return
        # Prefer C++ — that is what the reference solutions are written in.
        for option in options:
            haystack = f"{option.get('id','')} {option.get('label','')}".lower()
            if "c++" in haystack or "cpp" in haystack:
                self.language_id = option["id"]
                return
        self.language_id = options[0]["id"]

    def find_group_contest(self) -> None:
        """The monitored-round lookup the compete page does on load."""
        response = self.call(
            "GET /groups/contests/active", "GET",
            f"/groups/contests/active?platform=DOMJUDGE&externalId={self.args.contest}")
        if response.ok and response.data():
            self.group_contest_id = response.data().get("contestId")

    def read_statement(self, index: str) -> None:
        self.call("GET .../problems/{i}", "GET", f"{self.base()}/problems/{index}")
        # The arena fetches the PDF separately, through the backend's proxy.
        self.call("GET .../statement.pdf", "GET",
                  f"{self.base()}/problems/{index}/statement.pdf")

    def poll_submissions(self) -> list:
        response = self.call("GET .../submissions", "GET", f"{self.base()}/submissions")
        if not response.ok:
            return []
        rows = response.data() or []

        for row in rows:
            sid = row.get("id")
            if not sid or not row.get("finished"):
                continue
            if sid in self.seen_verdicts:
                continue
            self.seen_verdicts.add(sid)
            sent_at = self.pending.pop(sid, None)
            delay = (time.time() - sent_at) if sent_at else None
            self.metrics.note_verdict(row.get("verdict") or "UNKNOWN", delay)
        return rows

    def poll_rank(self) -> None:
        self.call("GET .../rank", "GET", f"{self.base()}/rank")

    def submit(self, index: str, expect: str) -> bool:
        source = self.solutions[index].get(expect)
        if not source or not self.language_id:
            return False

        response = self.call("POST .../submit", "POST", f"{self.base()}/submit", body={
            "index": index,
            "languageId": self.language_id,
            "source": source,
        })
        if response.ok and response.data():
            sid = response.data().get("id")
            if sid:
                self.pending[sid] = time.time()
        # The real page invalidates its submissions query the moment a submit succeeds, so
        # the new row appears immediately rather than up to a minute later. Reproducing that
        # matters: it is what turns one submit into a burst of 5-second polling, which is the
        # load pattern this whole exercise is about.
        return True

    def report_violations(self) -> None:
        """What the away-monitor posts while a monitored round is running."""
        if not self.group_contest_id:
            return
        events = [{
            "eventId": str(uuid.uuid4()),
            "type": "FOCUS_LOST",
            "detail": "simulated tab switch",
            "durationMs": self.rng.randint(1200, 45000),
            "occurredAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        }]
        self.call("POST .../violations", "POST",
                  f"/groups/contests/{self.group_contest_id}/violations",
                  body={"events": events})

    # ------------------------------------------------------------------- loop

    def run(self, deadline: float) -> None:
        if not self.login():
            return

        contest = self.open_contest()
        if contest is None:
            return

        self.load_languages()
        self.find_group_contest()

        problems = [p["index"] for p in (contest.get("problems") or [])]
        if not problems:
            self.metrics.note_error(
                f"{self.entry['username']}: the contest reports no problems — is the DOMjudge "
                f"contest populated and is the account's team mapped?")
            return

        plan = problems[:self.attempts]
        opened: set[str] = set()

        # Cadences, matching what the real page does.
        next_contest = time.time() + 60
        next_rank = time.time() + 30
        next_violation = time.time() + 15
        next_submissions = time.time() + 5
        # Think time before the first submission: even the fastest person reads the statement.
        next_action = time.time() + self.rng.uniform(5, 40) * self.args.think

        plan_index = 0
        attempt_number = 0

        while not STOP.is_set() and time.time() < deadline:
            now = time.time()

            if now >= next_submissions:
                rows = self.poll_submissions()
                anything_pending = any(not r.get("finished") for r in rows)
                next_submissions = now + (5 if anything_pending else 60)

            if now >= next_contest:
                self.open_contest()
                next_contest = now + 60

            if now >= next_rank:
                self.poll_rank()
                next_rank = now + 30

            if now >= next_violation and self.group_contest_id:
                # Not everyone leaves the window; a third of them do, occasionally.
                if self.rng.random() < 0.08:
                    self.report_violations()
                next_violation = now + 15

            if now >= next_action and plan_index < len(plan):
                index = plan[plan_index]

                if index not in opened:
                    self.read_statement(index)
                    opened.add(index)
                    next_action = now + self.rng.uniform(10, 60) * self.args.think
                elif not self.args.read_only:
                    # Weaker contestants get it wrong more often, and everyone eventually
                    # submits something that passes and moves on.
                    wrong_chance = 0.65 * (1 - self.skill)
                    if attempt_number == 0 and self.rng.random() < wrong_chance:
                        self.submit(index, self.rng.choice(["WA", "TLE", "RTE", "CE"]))
                        attempt_number += 1
                        next_action = now + self.rng.uniform(20, 90) * self.args.think
                    else:
                        self.submit(index, "AC")
                        plan_index += 1
                        attempt_number = 0
                        next_action = now + self.rng.uniform(30, 180) * self.args.think
                    next_submissions = now + 2
                else:
                    plan_index += 1
                    next_action = now + self.rng.uniform(20, 90) * self.args.think

            time.sleep(0.25)

        self.client.close()


# ----------------------------------------------------------------------- main

def load_solutions(problemset: Path) -> dict:
    """
    index -> {verdict -> source}, read from the generated solutions directory.

    Read once at start-up rather than per submission: opening a file inside the hot loop would
    put local disk latency into the numbers this script exists to produce.
    """
    solutions: dict[str, dict[str, str]] = defaultdict(dict)
    directory = problemset / "solutions"
    if not directory.is_dir():
        raise SystemExit(
            f"No solutions in {directory}. Run make_problemset.py first.")

    for path in sorted(directory.glob("*.cpp")):
        # Named LETTER-name.VERDICT.cpp
        stem = path.name[:-len(".cpp")]
        letter, _, rest = stem.partition("-")
        _, _, verdict = rest.rpartition(".")
        solutions[letter][verdict] = path.read_text()
    return dict(solutions)


def progress(metrics: Metrics, live: threading.Event, total: int) -> None:
    last_total = 0
    while live.is_set():
        time.sleep(5)
        with metrics._lock:                       # noqa: SLF001 - reporting thread
            requests = sum(len(v) for v in metrics.latencies.values())
            verdicts = sum(metrics.verdicts.values())
            errors = len(metrics.errors)
        rate = (requests - last_total) / 5
        last_total = requests
        elapsed = time.time() - metrics.started_at
        print(f"  [{elapsed:>5.0f}s] {requests:>7} requests  {rate:>6.1f}/s   "
              f"{verdicts:>4} verdicts   {errors:>3} errors", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--api", default="http://localhost:8080/api/v1")
    parser.add_argument("--roster", required=True)
    parser.add_argument("--contest", required=True, help="the DOMjudge contest id")
    parser.add_argument("--problemset", default=None,
                        help="the problemset directory (default: out/problemset)")
    parser.add_argument("--users", type=int, default=200)
    parser.add_argument("--duration", type=float, default=20,
                        help="how long to run, in minutes (default 20)")
    parser.add_argument("--ramp", type=float, default=120,
                        help="seconds over which to bring contestants online (default 120)")
    parser.add_argument("--think", type=float, default=1.0,
                        help="multiplier on think time; 0.2 makes everyone five times busier")
    parser.add_argument("--read-only", action="store_true",
                        help="never submit — measures read scaling without loading the judge")
    parser.add_argument("--timeout", type=float, default=30)
    parser.add_argument("--insecure", action="store_true")
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--out", default=None, help="write the report to this file as well")
    args = parser.parse_args()

    roster = json.loads(Path(args.roster).read_text())["participants"][:args.users]
    if not roster:
        raise SystemExit("The roster is empty.")

    here = Path(__file__).resolve().parent
    problemset = Path(args.problemset) if args.problemset else here / "out" / "problemset"
    solutions = load_solutions(problemset)

    metrics = Metrics()
    deadline = time.time() + args.duration * 60

    print(f"Simulating {len(roster)} contestants on DOMJUDGE/{args.contest}")
    print(f"  api        {args.api}")
    print(f"  duration   {args.duration:g} minutes")
    print(f"  ramp       {args.ramp:g} seconds")
    print(f"  mode       {'read-only' if args.read_only else 'submitting'}")
    if not args.read_only:
        print(f"  problems   {', '.join(sorted(solutions))}")
    print()

    live = threading.Event()
    live.set()
    reporter = threading.Thread(target=progress, args=(metrics, live, len(roster)),
                                daemon=True)
    reporter.start()

    threads = []
    stagger = args.ramp / max(len(roster), 1)

    def start(entry, index):
        # Each contestant gets its own seeded generator, so a run is reproducible and one
        # person's behaviour does not depend on how the threads happened to interleave.
        rng = random.Random(args.seed * 1000 + index)
        time.sleep(index * stagger)
        try:
            Contestant(entry, args, metrics, solutions, rng).run(deadline)
        except Exception as e:                                   # noqa: BLE001
            # A contestant thread that dies quietly takes its load off the server and makes
            # the run look healthier than it was. Whatever went wrong gets reported.
            metrics.note_error(
                f"{entry['username']} stopped early: {type(e).__name__}: {e}")

    try:
        for i, entry in enumerate(roster):
            thread = threading.Thread(target=start, args=(entry, i), daemon=True)
            thread.start()
            threads.append(thread)

        while any(t.is_alive() for t in threads) and time.time() < deadline + 30:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nStopping early…")
        STOP.set()

    STOP.set()
    for thread in threads:
        thread.join(timeout=10)
    live.clear()

    report = metrics.report(time.time() - metrics.started_at)
    print(report)

    if args.out:
        Path(args.out).write_text(report)
        print(f"Report written to {args.out}")

    # A run that could not sign anyone in, or that never saw a verdict when it was submitting,
    # did not measure what it set out to measure.
    signed_in = metrics.statuses.get("POST /auth/login", Counter())
    if not any(s < 400 for s in signed_in):
        print("No contestant signed in. Nothing here measured the arena.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
