#!/usr/bin/env python3
"""
Loads the local DOMjudge (stack.sh's "judge" profile) with what the load test sits.

    ./scripts/loadtest/setup_judge.py            # after stack.sh up

1. A contest, "loadtest", open from now for --hours (default 12): it has to cover the
   examination's window, and a long one covers a whole ramp of runs.
2. The six problems of scripts/simulation/out/problemset, as the contest's problems.
3. The roster's teams and their team accounts — each examination candidate submits as their
   own DOMjudge login, and CPIntel checks every one when the roster is imported.
4. That C++ can be submitted and judged.

Re-runnable: what is already there is left alone. Writes out/judge.json, which
provision_exam.py reads for the judge's address, the admin account and the contest.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

import requests

HERE = Path(__file__).resolve().parent
SIM = HERE.parent / "simulation" / "out"
CONTAINER = "cpintel-lt-domserver"
CONTEST = "loadtest"
# How the backend reaches the judge: by name, on the stack's own network.
INTERNAL_URL = "http://domserver"


def admin_password(saved: Path) -> str:
    out = subprocess.run(
        ["docker", "exec", CONTAINER, "cat",
         "/opt/domjudge/domserver/etc/initial_admin_password.secret"],
        check=True, capture_output=True, text=True)
    password = out.stdout.strip()
    # A domserver recreated over its database no longer knows the password it was installed
    # with and writes "[unknown]"; the one saved on the first run still works.
    if password == "[unknown]" and saved.exists():
        password = json.loads(saved.read_text())["password"]
    return password


class Judge:
    def __init__(self, url: str, password: str):
        self.api = url.rstrip("/") + "/api/v4"
        self.s = requests.Session()
        self.s.auth = ("admin", password)

    def get(self, path: str):
        r = self.s.get(self.api + path, timeout=60)
        r.raise_for_status()
        return r.json()

    def upload(self, path: str, field: str, name: str, content: bytes, context: str):
        r = self.s.post(self.api + path, files={field: (name, content)}, timeout=300)
        if not r.ok:
            raise SystemExit(f"{context}: HTTP {r.status_code} {r.text[:400]}")
        return r.json() if r.content else None


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--url", default="http://127.0.0.1:12345", help="the judge, from this machine")
    p.add_argument("--hours", type=float, default=12, help="how long the contest stays open")
    p.add_argument("--out", default=str(HERE / "out" / "judge.json"))
    args = p.parse_args()

    password = admin_password(Path(args.out))
    dj = Judge(args.url, password)
    info = dj.get("/info")
    print(f"DOMjudge {info.get('domjudge', {}).get('version', '?')} at {args.url}")

    # 1. The contest.
    contest = next((c for c in dj.get("/contests") if c.get("shortname") == CONTEST), None)
    if contest is None:
        start = datetime.now(timezone.utc).replace(microsecond=0) - timedelta(minutes=1)
        hours = int(args.hours)
        minutes = int(round((args.hours - hours) * 60))
        spec = {"id": CONTEST, "name": "CPIntel load test", "formal_name": "CPIntel load test",
                "start_time": start.isoformat().replace("+00:00", "Z"),
                "duration": f"{hours}:{minutes:02d}:00.000",
                "scoreboard_freeze_duration": "0:00:00.000", "penalty_time": 20}
        dj.upload("/contests", "json", "contest.json", json.dumps(spec).encode(),
                  "creating the contest")
        contest = next(c for c in dj.get("/contests") if c.get("shortname") == CONTEST)
    cid = contest["id"]
    print(f"contest {cid}: {contest.get('start_time')} for {contest.get('duration')}")
    ends = contest.get("end_time")
    if ends and datetime.fromisoformat(ends.replace("Z", "+00:00")) < \
            datetime.now(timezone.utc) + timedelta(hours=2):
        print(f"  it ends at {ends}, inside two hours: stack.sh down and start again for a "
              f"fresh one", file=sys.stderr)

    # 2. The problems.
    have = {pr.get("id") for pr in dj.get(f"/contests/{cid}/problems")}
    for z in sorted((SIM / "problemset").glob("*.zip")):
        label = z.name.split("-", 1)[0].upper()
        if z.stem in have:
            continue
        dj.upload(f"/contests/{cid}/problems", "zip", z.name, z.read_bytes(),
                  f"importing {z.name}")
        print(f"  problem {label}: {z.name}")
    # DOMjudge 9 labels a problem after its zip's file name; the paper and the simulator's
    # solutions go by letter. A contest's label is only set when the problem is linked to it.
    for pr in dj.get(f"/contests/{cid}/problems"):
        label = pr["id"].split("-", 1)[0].upper()
        if pr["label"] != label:
            dj.s.delete(f"{dj.api}/contests/{cid}/problems/{pr['id']}", timeout=60).raise_for_status()
            r = dj.s.put(f"{dj.api}/contests/{cid}/problems/{pr['id']}", timeout=60,
                         json={"label": label, "ordinal": ord(label) - ord("A")})
            if not r.ok:
                raise SystemExit(f"labelling {pr['id']}: HTTP {r.status_code} {r.text[:300]}")
    problems = dj.get(f"/contests/{cid}/problems")
    print(f"{len(problems)} problems: {' '.join(sorted(pr['label'] for pr in problems))}")

    # 3. The teams and their accounts. The teams' category comes first; teams.tsv puts them in 3.
    roster = SIM / "roster" / "domjudge"
    groups = {str(g["id"]) for g in dj.get(f"/contests/{cid}/groups")}
    if "3" not in groups:
        dj.upload("/users/groups", "tsv", "groups.tsv", b"groups\t1\n3\tParticipants\n",
                  "creating the Participants category")
    teams = {t.get("name") for t in dj.get(f"/contests/{cid}/teams")}
    if "Team 001" not in teams:
        dj.upload("/users/teams", "tsv", "teams.tsv", (roster / "teams.tsv").read_bytes(),
                  "importing the teams")
        dj.upload("/users/accounts", "tsv", "accounts.tsv",
                  (roster / "accounts.tsv").read_bytes(), "importing the team accounts")
    teams = dj.get(f"/contests/{cid}/teams")
    print(f"{len(teams)} teams in the contest")

    # A team account has to be able to sign in and land on its own team.
    first = json.loads((roster / "accounts.json").read_text())[0]
    me = requests.get(dj.api + "/user", auth=(first["username"], first["password"]), timeout=30)
    if not me.ok or str(me.json().get("team_id")) != str(first["team_id"]):
        print(f"{first['username']} cannot sign in as team {first['team_id']}: "
              f"HTTP {me.status_code} {me.text[:200]}", file=sys.stderr)
        return 1
    print(f"{first['username']} signs in as team {me.json().get('team_id')}")

    # 4. C++ has to be accepted, and judged.
    langs = {lang["id"]: lang for lang in dj.get(f"/contests/{cid}/languages")}
    if "cpp" not in langs:
        print(f"C++ is not enabled for submission (enabled: {', '.join(langs) or 'none'}). "
              f"Turn it on under Jury → Languages → C++ (allow submit, allow judge).",
              file=sys.stderr)
        return 1
    print(f"languages: {', '.join(sorted(langs))}")

    # The judgehosts, which stack.sh started once the domserver was up.
    for _ in range(30):
        hosts = [h for h in dj.get("/judgehosts") if h.get("enabled") and not h.get("hidden")]
        if hosts:
            break
        time.sleep(4)
    print(f"judgehosts: {', '.join(h['hostname'] for h in hosts) or 'NONE'}")
    if not hosts:
        print("No judgehost has registered; see docker logs cpintel-lt-judgehost-0.",
              file=sys.stderr)
        return 1

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"url": INTERNAL_URL, "user": "admin", "password": password,
                               "contest": str(cid)}, indent=2))
    out.chmod(0o600)
    print(f"Wrote {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
