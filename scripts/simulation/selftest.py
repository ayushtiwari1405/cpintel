#!/usr/bin/env python3
"""
Proves the harness works, without needing CPIntel or DOMjudge to exist yet.

    ./scripts/simulation/selftest.py

Starts the stub server, runs a short simulation against it, and checks the result is the shape
it should be: everybody signed in, every request came back 2xx, submissions were made, and
verdicts came back — including wrong ones, which is what shows the whole submit-poll-resolve
loop is being exercised rather than just the happy path.

Run this before pointing the simulator at anything real. When a 200-person run then reports
errors, this is what tells you they are the server's and not the harness's.
"""

from __future__ import annotations

import json
import re
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def check(condition: bool, description: str) -> bool:
    print(f"  {'ok  ' if condition else 'FAIL'}  {description}")
    return condition


def main() -> int:
    problemset = HERE / "out" / "problemset"
    if not (problemset / "solutions").is_dir():
        print("Building the problem set first…")
        subprocess.run([sys.executable, str(HERE / "make_problemset.py")], check=True)

    roster = HERE / "out" / "roster" / "roster.json"
    if not roster.exists():
        print("Building the roster first…")
        subprocess.run([sys.executable, str(HERE / "make_roster.py")], check=True)

    port = free_port()
    print(f"\nStarting the stub server on port {port}…")
    stub = subprocess.Popen(
        [sys.executable, str(HERE / "stub_server.py"), "--port", str(port)],
        stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)

    try:
        # Wait for it to accept connections rather than sleeping a guessed interval.
        deadline = time.time() + 15
        while time.time() < deadline:
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=0.5):
                    break
            except OSError:
                time.sleep(0.2)
        else:
            raise SystemExit("The stub server never came up.")

        with tempfile.TemporaryDirectory() as tmp:
            report_path = Path(tmp) / "report.txt"
            print("Running a 45-second simulation with 30 contestants…\n")
            result = subprocess.run([
                sys.executable, str(HERE / "simulate.py"),
                "--api", f"http://127.0.0.1:{port}/api/v1",
                "--roster", str(roster),
                "--contest", "stub",
                "--users", "30",
                "--duration", "0.75",
                "--ramp", "4",
                "--think", "0.1",
                "--out", str(report_path),
            ], capture_output=True, text=True)

            report = report_path.read_text() if report_path.exists() else result.stdout

        print("\nChecks")
        passed = True
        passed &= check(result.returncode == 0,
                        f"the simulator exited cleanly (got {result.returncode})")
        passed &= check("every request returned 2xx" in report,
                        "no request returned an error status")
        passed &= check("ERRORS" not in report, "no errors were recorded")

        logins = re.search(r"POST /auth/login\s+(\d+)", report)
        passed &= check(bool(logins) and int(logins.group(1)) == 30,
                        f"all 30 contestants signed in "
                        f"(saw {logins.group(1) if logins else 'none'})")

        submits = re.search(r"POST \.\.\./submit\s+(\d+)", report)
        passed &= check(bool(submits) and int(submits.group(1)) > 20,
                        f"submissions were made "
                        f"(saw {submits.group(1) if submits else '0'})")

        polls = re.search(r"GET \.\.\./submissions\s+(\d+)", report)
        passed &= check(bool(polls) and int(polls.group(1)) > 30,
                        f"the console polled for verdicts "
                        f"(saw {polls.group(1) if polls else '0'})")

        passed &= check("statement.pdf" in report,
                        "statement PDFs were fetched through the proxy")
        passed &= check("POST .../violations" in report,
                        "monitoring reports were posted")
        passed &= check("OK " in report, "accepted verdicts came back")

        wrong = any(v in report for v in
                    ("WRONG_ANSWER", "COMPILATION_ERROR", "TIME_LIMIT_EXCEEDED",
                     "RUNTIME_ERROR"))
        passed &= check(wrong, "non-accepted verdicts came back too")
        passed &= check("submit → verdict" in report,
                        "submit-to-verdict timing was measured")

        if not passed:
            print("\nThe report was:\n")
            print(report)
            print("\nstderr from the simulator:\n", result.stderr[:3000])
            return 1

        print("\nThe harness works. Point it at the real deployment next:")
        print("  ./scripts/simulation/provision.py --help")
        print("  ./scripts/simulation/simulate.py  --help")
        return 0

    finally:
        stub.terminate()
        try:
            stub.wait(timeout=5)
        except subprocess.TimeoutExpired:
            stub.kill()


if __name__ == "__main__":
    raise SystemExit(main())
