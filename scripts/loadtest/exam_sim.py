#!/usr/bin/env python3
"""
Plays an examination room against CPIntel and reports what the server did.

    ./scripts/loadtest/exam_sim.py --users 25  --insecure    # then 50, 100, 200
    ./scripts/loadtest/exam_sim.py --users 200 --insecure --out out/report-200.txt

Reads out/exam.json (provision_exam.py). Each candidate is a thread doing what the examination
page does, at the page's own cadence:

  before the start   signs in with the code on their slip, inside the hour the codes work for,
                     and opens the paper
  at the start       enters and unlocks with the room password — the whole room inside a few
                     seconds, which is the burst a paper actually opens with
  throughout         a monitor heartbeat every 15 s, an occasional focus loss reported, the
                     paper and its clock re-read every minute, the leaderboard every minute,
                     and the access token renewed through the refresh cookie as a browser would
  per problem        opens it and reads its statement, presses Run on the samples (on the
                     server's runner, which is examination-only), submits — sometimes wrong
                     first — and polls its submissions every 5 s until the verdict lands

Everything goes through nginx, as the room would. --users is how many of the provisioned
candidates take part; the rest stay away. Ramp up rather than starting at 200 — run 25, 50,
100, 200 and compare: a number means something next to the same number at a different load.

The report is simulate.py's: latency per endpoint (p50/p90/p99/max), every non-2xx status,
verdicts, and submit → verdict times. Watch Grafana alongside (README.md).
"""

from __future__ import annotations

import argparse
import json
import random
import sys
import threading
import time
import uuid
import zipfile
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
SIM = HERE.parent / "simulation"
sys.path.insert(0, str(SIM))

from simclient import Client, Response  # noqa: E402
from simulate import Metrics, load_solutions, progress  # noqa: E402

STOP = threading.Event()

HEARTBEAT_S = 15
REFRESH_EVERY_S = 12 * 60          # the access token lives 15 minutes
POLL_SUBMISSIONS_S = 5
REREAD_S = 60

# How a candidate submits a problem: mostly right first time, some wrong first.
FIRST_TRY = [("AC", 0.6), ("WA", 0.25), ("CE", 0.05), ("RTE", 0.05), ("TLE", 0.05)]


def parse_time(s: str) -> float:
    return datetime.fromisoformat(s.replace("Z", "+00:00")).astimezone(timezone.utc).timestamp()


def load_samples(problemset: Path) -> dict:
    """letter -> [(input, answer)], from the problem zips the judge was loaded with."""
    manifest = json.loads((problemset / "manifest.json").read_text())
    out = {}
    for prob in manifest["problems"]:
        with zipfile.ZipFile(problemset / prob["zip"]) as z:
            names = sorted(n for n in z.namelist() if n.startswith("data/sample/"))
            ins = [n for n in names if n.endswith(".in")]
            out[prob["letter"]] = [(z.read(n).decode(), z.read(n[:-3] + ".ans").decode())
                                   for n in ins]
    return out


class Candidate:
    def __init__(self, entry: dict, exam: dict, args, metrics: Metrics, solutions: dict,
                 samples: dict, rng: random.Random):
        self.entry = entry
        self.exam = exam
        self.args = args
        self.metrics = metrics
        self.solutions = solutions
        self.samples = samples
        self.rng = rng
        self.client = Client(exam["api"], timeout=args.timeout, verify_tls=not args.insecure)
        self.token = None
        self.refresh_cookie = None
        self.refreshed_at = 0.0
        self.language = None
        self.ref = exam["contestRef"]
        self.exam_id = exam["examId"]

    # ── plumbing ───────────────────────────────────────────────────────────

    def call(self, label: str, method: str, path: str, **kw) -> Response | None:
        try:
            r = self.client.request(method, path, token=self.token, **kw)
        except Exception as e:                                   # noqa: BLE001
            self.metrics.record_transport(label)
            self.metrics.note_error(f"{self.entry['username']} {label}: {type(e).__name__}: {e}")
            return None
        self.metrics.record(label, r)
        if r.status >= 500:
            self.metrics.note_error(f"{self.entry['username']} {label}: {r.status} {r.message()}")
        return r

    def sign_in(self) -> bool:
        for attempt in range(8):
            r = self.call("POST /auth/login (exam code)", "POST", "/auth/login",
                          body={"identifier": self.entry["username"],
                                "password": self.entry["passcode"]})
            if r is not None and r.ok:
                self.token = r.data()["accessToken"]
                self.refresh_cookie = r.cookie("cpintel_refresh")
                self.refreshed_at = time.time()
                return True
            if r is not None and r.status == 429:
                self.metrics.note_throttle()
                time.sleep(2 + attempt * 3 + self.rng.random() * 3)
                continue
            if r is not None:
                self.metrics.note_error(f"{self.entry['username']} sign-in: {r.status} {r.message()}")
            return False
        return False

    def renew(self) -> None:
        if not self.refresh_cookie:
            return
        r = self.call("POST /auth/refresh", "POST", "/auth/refresh",
                      headers={"Cookie": f"cpintel_refresh={self.refresh_cookie}"})
        if r is not None and r.ok:
            self.token = r.data()["accessToken"]
            self.refresh_cookie = r.cookie("cpintel_refresh") or self.refresh_cookie
            self.refreshed_at = time.time()

    def report_event(self, kind: str, label: str = None, duration_ms: int = None) -> None:
        self.call("POST /exams/{id}/events", "POST", f"/exams/{self.exam_id}/events", body={
            "events": [{"eventId": uuid.uuid4().hex, "type": kind, "problemLabel": label,
                        "durationMs": duration_ms,
                        "occurredAt": datetime.now(timezone.utc).isoformat()}]})

    # ── what the page does ─────────────────────────────────────────────────

    def background(self) -> None:
        """Heartbeat, re-reads, token renewal and the odd glance away, until the paper ends."""
        next_beat = next_read = time.time()
        while not STOP.is_set() and time.time() < self.deadline:
            now = time.time()
            if now >= next_beat:
                self.call("POST /exams/{id}/monitor/heartbeat", "POST",
                          f"/exams/{self.exam_id}/monitor/heartbeat")
                next_beat = now + HEARTBEAT_S
            if now >= next_read:
                self.call("GET /exams/{id}", "GET", f"/exams/{self.exam_id}")
                self.call("GET /exams/{id}/leaderboard", "GET",
                          f"/exams/{self.exam_id}/leaderboard")
                next_read = now + REREAD_S + self.rng.uniform(-5, 5)
            if now - self.refreshed_at > REFRESH_EVERY_S:
                self.renew()
            if self.rng.random() < 0.002:     # about one glance away per ~8 minutes
                away = self.rng.randint(2_000, 20_000)
                self.report_event("FOCUS_LOST")
                self.report_event("FOCUS_REGAINED", duration_ms=away)
            time.sleep(1)

    def run_samples(self, letter: str, source: str) -> None:
        tests = [{"input": i, "expected": a, "label": f"Sample {n + 1}"}
                 for n, (i, a) in enumerate(self.samples.get(letter, []))]
        if not tests:
            return
        r = self.call("POST /run", "POST", "/run", body={
            "language": "cpp", "source": source, "tests": tests,
            "platform": "DOMJUDGE", "contestId": self.ref})
        if r is not None and r.status == 503:
            self.metrics.note_error(f"{self.entry['username']} Run: runner busy (503)")
        elif r is not None and r.ok:
            # A run the runner could not attempt is still a 200, with the reason inside; the
            # status alone hid an hour of the runner refusing everything.
            # A source that does not compile is one of the simulated mistakes, not a failure.
            error = (r.data() or {}).get("error")
            if error:
                self.metrics.note_error(f"{self.entry['username']} Run: {error}")

    def submit_and_wait(self, letter: str, verdict: str) -> str | None:
        source = self.solutions.get(letter, {}).get(verdict) \
            or self.solutions.get(letter, {}).get("AC")
        if source is None:
            return None
        sent = time.time()
        r = self.call("POST /compete/.../submit", "POST",
                      f"/compete/DOMJUDGE/{self.ref}/submit",
                      body={"index": letter, "languageId": self.language, "source": source})
        if r is None or not r.ok:
            return None
        sub_id = (r.data() or {}).get("id")
        while not STOP.is_set() and time.time() < self.deadline:
            time.sleep(POLL_SUBMISSIONS_S)
            r = self.call("GET /compete/.../submissions", "GET",
                          f"/compete/DOMJUDGE/{self.ref}/submissions")
            if r is None or not r.ok:
                continue
            mine = next((s for s in (r.data() or []) if s.get("id") == sub_id), None)
            if mine and mine.get("finished"):
                self.metrics.note_verdict(mine.get("verdict") or "?", time.time() - sent)
                return mine.get("verdict")
        return None

    def work_problem(self, letter: str) -> None:
        self.report_event("PROBLEM_OPENED", label=letter)
        self.call("GET /compete/.../problems/{i}", "GET",
                  f"/compete/DOMJUDGE/{self.ref}/problems/{letter}")
        self.think(60, 300)
        first = self.rng.choices([v for v, _ in FIRST_TRY], [w for _, w in FIRST_TRY])[0]
        source = self.solutions.get(letter, {}).get(first) or self.solutions[letter]["AC"]
        if self.rng.random() < 0.8:
            self.run_samples(letter, source)
        verdict = self.submit_and_wait(letter, first)
        if verdict is not None and verdict.upper() not in ("CORRECT", "ACCEPTED", "OK", "AC"):
            self.think(60, 240)
            self.run_samples(letter, self.solutions[letter]["AC"])
            self.submit_and_wait(letter, "AC")

    def think(self, lo: float, hi: float) -> None:
        end = time.time() + self.rng.uniform(lo, hi) * self.args.think
        while not STOP.is_set() and time.time() < min(end, self.deadline):
            time.sleep(1)

    def run(self, sign_in_at: float, starts_at: float, deadline: float) -> None:
        self.deadline = deadline
        while time.time() < sign_in_at and not STOP.is_set():
            time.sleep(0.5)
        if not self.sign_in():
            return
        self.call("GET /exams/{id}", "GET", f"/exams/{self.exam_id}")

        # The bell: everybody within a few seconds of it.
        bell = starts_at + self.rng.uniform(0, self.args.bell_spread)
        while time.time() < bell and not STOP.is_set():
            time.sleep(0.2)
        self.call("POST /exams/{id}/enter", "POST", f"/exams/{self.exam_id}/enter")
        r = self.call("POST /exams/{id}/unlock", "POST", f"/exams/{self.exam_id}/unlock",
                      body={"examPassword": self.exam["roomPassword"],
                            "passcode": self.entry["passcode"]})
        if r is None or not r.ok:
            return

        langs = self.call("GET /compete/.../languages", "GET",
                          f"/compete/DOMJUDGE/{self.ref}/languages")
        options = (langs.data() if langs is not None and langs.ok else None) or []
        self.language = next((o["id"] for o in options
                              if "c++" in (o.get("label") or "").lower()
                              or o.get("id") in ("cpp", "c++")), "cpp")

        threading.Thread(target=self.background, daemon=True).start()

        letters = [l for l in self.exam["problems"] if l in self.solutions]
        self.rng.shuffle(letters)
        # The first minutes of a paper are when everybody presses Run on problem A at once.
        if letters and self.rng.random() < self.args.run_burst:
            self.run_samples(letters[0], self.solutions[letters[0]]["AC"])
        for letter in letters:
            if STOP.is_set() or time.time() >= deadline:
                break
            self.work_problem(letter)
        while not STOP.is_set() and time.time() < deadline:
            time.sleep(1)


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--exam", default=str(HERE / "out" / "exam.json"))
    p.add_argument("--problemset", default=str(SIM / "out" / "problemset"))
    p.add_argument("--users", type=int, default=25)
    p.add_argument("--duration", type=float, default=30,
                   help="minutes of the paper to simulate after the bell (default 30)")
    p.add_argument("--sign-in-spread", type=float, default=300,
                   help="seconds before the bell over which the room signs in (default 300)")
    p.add_argument("--bell-spread", type=float, default=10,
                   help="seconds after the bell over which the room unlocks (default 10)")
    p.add_argument("--run-burst", type=float, default=0.7,
                   help="share of the room pressing Run in the first minute (default 0.7)")
    p.add_argument("--think", type=float, default=1.0,
                   help="multiplier on thinking time; 0.3 makes everybody faster")
    p.add_argument("--timeout", type=float, default=30)
    p.add_argument("--insecure", action="store_true")
    p.add_argument("--seed", type=int, default=7)
    p.add_argument("--out", default=None, help="write the report here as well")
    args = p.parse_args()

    exam = json.loads(Path(args.exam).read_text())
    roster = exam["candidates"][:args.users]
    starts_at = parse_time(exam["startsAt"])
    now = time.time()
    if starts_at - now > 3600:
        raise SystemExit("The paper opens more than an hour from now; codes do not work yet.")
    if parse_time(exam["endsAt"]) < now + 60:
        raise SystemExit("The paper has ended. Provision a new one.")
    if starts_at < now + args.sign_in_spread:
        print(f"Note: the paper opens in {max(0, starts_at - now):.0f}s, less than the "
              f"{args.sign_in_spread:.0f}s sign-in spread; the room signs in as fast as it can.")
    deadline = min(parse_time(exam["endsAt"]), max(starts_at, now) + args.duration * 60)

    problemset = Path(args.problemset)
    solutions = load_solutions(problemset)
    samples = load_samples(problemset)
    metrics = Metrics()

    print(f"{len(roster)} candidates, examination {exam['examId']} ({exam['contestRef']})")
    print(f"  bell       {exam['startsAt']}  (in {max(0, starts_at - now):.0f}s)")
    print(f"  simulating {args.duration:g} minutes after it; problems {', '.join(exam['problems'])}")
    print()

    live = threading.Event()
    live.set()
    threading.Thread(target=progress, args=(metrics, live, len(roster)), daemon=True).start()

    threads = []
    for i, entry in enumerate(roster):
        rng = random.Random(args.seed * 1000 + i)
        sign_in_at = max(now, starts_at - args.sign_in_spread) + rng.uniform(0, args.sign_in_spread)
        sign_in_at = min(sign_in_at, max(now, starts_at - 5))
        candidate = Candidate(entry, exam, args, metrics, solutions, samples, rng)

        def go(c=candidate, at=sign_in_at, name=entry["username"]):
            try:
                c.run(at, starts_at, deadline)
            except Exception as e:                                # noqa: BLE001
                metrics.note_error(f"{name} stopped early: {type(e).__name__}: {e}")

        t = threading.Thread(target=go, daemon=True)
        t.start()
        threads.append(t)

    try:
        while any(t.is_alive() for t in threads) and time.time() < deadline + 60:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nStopping early…")
    STOP.set()
    for t in threads:
        t.join(timeout=10)
    live.clear()

    report = metrics.report(time.time() - metrics.started_at)
    print(report)
    if args.out:
        Path(args.out).write_text(report)
        print(f"Report written to {args.out}")

    signed = metrics.statuses.get("POST /auth/login (exam code)", Counter())
    if not any(0 < s < 400 for s in signed):
        print("No candidate signed in; nothing here measured the examination.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
