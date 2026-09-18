#!/usr/bin/env python3
"""
A stand-in CPIntel, so the harness can be tested before there is anything to test it against.

    ./scripts/simulation/stub_server.py --port 8099

It answers the compete, auth and group endpoints the simulator uses, with the same response
shapes the real backend produces: the ``{success, message, data}`` envelope, a contest with
problems, statements with a real PDF, submissions that sit in TESTING for a few seconds and
then resolve to a verdict, a rank, and the violation-report endpoint.

Why this exists. The simulator is the thing that will be pointed at a 200-person contest, and
a bug in it is indistinguishable from a bug in the arena at exactly the moment nobody has time
to tell them apart. Running it against a server whose behaviour is known settles which is
which. ``selftest.py`` does that automatically.

It is a test double and nothing more: no database, no authentication worth the name, and it
happily accepts any password. Never point anything real at it.
"""

from __future__ import annotations

import argparse
import json
import random
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

sys.path.insert(0, str(Path(__file__).resolve().parent))

from pdfgen import Pdf          # noqa: E402
from problems import PROBLEMS   # noqa: E402

#: How long a submission sits in TESTING before the stub resolves it — roughly what a real
#: judge takes for a small problem, so the simulator's polling logic is genuinely exercised.
JUDGE_SECONDS = 3.0

STARTED_AT = time.time()

_lock = threading.Lock()
_submissions: dict[str, list] = {}
_next_id = [1000]

VERDICT_FOR = {
    "AC": "OK", "WA": "WRONG_ANSWER", "TLE": "TIME_LIMIT_EXCEEDED",
    "RTE": "RUNTIME_ERROR", "CE": "COMPILATION_ERROR",
}


def statement_pdf_bytes() -> bytes:
    pdf = Pdf()
    pdf.heading("Stub statement")
    pdf.para("This PDF is produced by the simulation's stub server so the arena's statement "
             "pane has something real to render.")
    return pdf.build()


PDF_BYTES = statement_pdf_bytes()


def guess_verdict(source: str) -> str:
    """
    Works out which of the generated solutions was submitted.

    Matched on the marker comments the wrong solutions carry, so the stub returns the verdict
    the submission was *meant* to draw. That is what lets the self-test assert a spread of
    verdicts rather than just checking that something came back.
    """
    lowered = source.lower()
    if "missing semicolon" in lowered:
        return "CE"
    if "quadratic" in lowered or "far too slow" in lowered:
        return "TLE"
    if "/z" in source and "int z=0" in source.replace(" ", ""):
        return "RTE"
    for marker in ("subtracts instead", "forgets that the input", "clamps the running",
                   "upper median", "only ever steps right", "makes every answer 1"):
        if marker in lowered:
            return "WA"
    return "AC"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"          # keep-alive, like the real server
    # Without this the stub's own Nagle/delayed-ACK interaction adds a flat ~40ms to every
    # small response, which shows up in the self-test report as latency the server did not
    # actually have. Tomcat disables Nagle by default, so this matches the real backend.
    disable_nagle_algorithm = True

    def log_message(self, *args) -> None:  # noqa: D102 - silence the default access log
        pass

    # ------------------------------------------------------------------ output

    def _send(self, payload, status: int = 200, raw: bytes = None,
              content_type: str = "application/json") -> None:
        if raw is None:
            body = json.dumps({
                "success": 200 <= status < 300,
                "message": None,
                "data": payload,
                "timestamp": "2026-09-03T00:00:00Z",
            }).encode()
        else:
            body = raw
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        if not length:
            return {}
        try:
            return json.loads(self.rfile.read(length))
        except ValueError:
            return {}

    def _team(self) -> str:
        """The bearer token doubles as the contestant's identity here."""
        auth = self.headers.get("Authorization") or ""
        return auth.replace("Bearer ", "").strip() or "anonymous"

    # ------------------------------------------------------------------ routes

    def do_POST(self) -> None:      # noqa: N802
        path = urlparse(self.path).path
        body = self._read_json()

        if path.endswith("/auth/login"):
            email = body.get("email", "unknown")
            self._send({"accessToken": f"tok-{email}", "refreshToken": f"ref-{email}",
                        "user": {"username": email.split("@")[0]}})
            return

        if path.endswith("/auth/refresh"):
            token = body.get("refreshToken", "ref-unknown")
            self._send({"accessToken": token.replace("ref-", "tok-"),
                        "refreshToken": token, "user": {}})
            return

        if path.endswith("/submit"):
            index = body.get("index", "A")
            verdict = VERDICT_FOR[guess_verdict(body.get("source", ""))]
            with _lock:
                _next_id[0] += 1
                sid = str(_next_id[0])
                _submissions.setdefault(self._team(), []).append({
                    "id": sid, "index": index, "problemName": f"Problem {index}",
                    "language": body.get("languageId"), "verdict": "TESTING",
                    "passedTestCount": None, "timeConsumedMillis": None,
                    "memoryConsumedBytes": None,
                    "createdAt": "2026-09-03T10:00:00Z", "finished": False,
                    "url": f"http://stub/submissions/{sid}",
                    "_resolveAt": time.time() + JUDGE_SECONDS * (0.6 + random.random()),
                    "_verdict": verdict,
                })
            self._send({"id": sid, "index": index, "problemName": None,
                        "language": body.get("languageId"), "verdict": "TESTING",
                        "passedTestCount": None, "timeConsumedMillis": None,
                        "memoryConsumedBytes": None, "createdAt": "2026-09-03T10:00:00Z",
                        "finished": False, "url": f"http://stub/submissions/{sid}"})
            return

        if "/violations" in path:
            self._send(len(body.get("events", [])))
            return

        self._send(None, status=404)

    def do_GET(self) -> None:       # noqa: N802
        parsed = urlparse(self.path)
        path = parsed.path

        if path.endswith("/statement.pdf"):
            self._send(None, raw=PDF_BYTES, content_type="application/pdf")
            return

        if path.endswith("/languages"):
            self._send([{"id": "cpp", "label": "C++ 17"},
                        {"id": "python3", "label": "Python 3"}])
            return

        if path.endswith("/submissions"):
            now = time.time()
            with _lock:
                rows = _submissions.get(self._team(), [])
                for row in rows:
                    if not row["finished"] and now >= row["_resolveAt"]:
                        row["finished"] = True
                        row["verdict"] = row["_verdict"]
                        row["timeConsumedMillis"] = random.randint(10, 900)
                out = [{k: v for k, v in row.items() if not k.startswith("_")}
                       for row in rows]
            self._send(list(reversed(out)))
            return

        if path.endswith("/rank"):
            with _lock:
                solved = sum(1 for r in _submissions.get(self._team(), [])
                             if r.get("verdict") == "OK")
            self._send({"rank": random.randint(1, 200), "points": float(solved),
                        "penalty": solved * 20, "solvedCount": solved,
                        "frozen": False, "participating": True,
                        "fetchedAt": "2026-09-03T10:00:00Z"})
            return

        if path.endswith("/contests/active"):
            self._send({"contestId": 1, "groupId": 1, "groupName": "Simulation Cohort",
                        "platform": "DOMJUDGE", "externalId": "stub",
                        "name": "Stub Round", "url": None,
                        "startsAt": "2026-09-03T09:00:00Z", "endsAt": "2026-09-03T14:00:00Z",
                        "lockdownRequired": True, "status": "LIVE",
                        "standingsRefreshedAt": None, "standingsError": None})
            return

        if "/problems/" in path:
            index = path.rstrip("/").split("/")[-1]
            self._send({
                "contestId": "stub", "index": index, "name": f"Problem {index}",
                "rating": None, "tags": [], "timeLimit": "2 seconds",
                "memoryLimit": None, "inputFile": None, "outputFile": None,
                "legendHtml": None, "inputSpecHtml": None, "outputSpecHtml": None,
                "noteHtml": None,
                "samples": [{"input": "1 2\n", "output": "3\n"}],
                "url": "http://stub/problem", "statementAvailable": True,
                "statementPdfUrl": f"/api/v1/compete/DOMJUDGE/stub/problems/{index}"
                                   f"/statement.pdf",
            })
            return

        if "/compete/DOMJUDGE/" in path:
            elapsed = time.time() - STARTED_AT
            self._send({
                "id": path.rstrip("/").split("/")[-1], "name": "Stub Round",
                "platform": "DOMJUDGE", "phase": "CODING", "running": True,
                "frozen": False, "startsAt": "2026-09-03T09:00:00Z",
                "durationSeconds": 18000, "secondsUntilStart": 0,
                "secondsRemaining": max(0, 18000 - int(elapsed)),
                "submissionsOpen": True, "submissionsClosedReason": None,
                "personalFilesEnabled": True, "statementFormat": "PDF",
                "problems": [{"index": p.letter, "name": p.title,
                              "points": None, "rating": None} for p in PROBLEMS],
                "url": "http://stub/team",
            })
            return

        self._send(None, status=404)


def serve(port: int) -> ThreadingHTTPServer:
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8099)
    args = parser.parse_args()

    serve(args.port)
    print(f"Stub CPIntel on http://127.0.0.1:{args.port}/api/v1  (Ctrl-C to stop)")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
