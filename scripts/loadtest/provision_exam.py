#!/usr/bin/env python3
"""
Sets up an examination in CPIntel for the load test, against a DOMjudge that already has the
problems and the teams.

    ./scripts/loadtest/provision_exam.py --insecure --users 200 --starts-in 10 --duration 120

With the local judge, the judge's address, account and contest come from out/judge.json
(setup_judge.py). For a judge elsewhere, name them:

        --domjudge-url http://judge.example:12345 \\
        --dj-service-user admin --dj-service-password '...' --dj-contest 3

In order, each step reusing what an earlier run already made:

1. Signs in as the load test's super admin (CPINTEL_ADMIN_* in out/loadtest.env).
2. Creates the classroom on that DOMjudge, and checks the judge answers.
3. Creates the team, and imports the roster into it with every candidate's DOMjudge login
   attached — CPIntel submits as each candidate's own team, so the judge must know them all
   (import scripts/simulation/out/roster/domjudge/accounts.tsv there first).
4. Creates the examination over the judge contest, takes its problems from the judge (one mark
   each), assigns the team, and publishes it.
5. Issues every candidate their examination password and sets a room password.

Writes out/exam.json — the examination, the room password and each candidate's code — for
exam_sim.py. The codes are secrets of a throwaway deployment, and the file is gitignored.
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "simulation"))

from simclient import Client  # noqa: E402

CLASSROOM_NAME = "Load test"
TEAM_NAME = "Load test candidates"
EXAM_NAME = "Load test examination"


def iso(moment: datetime) -> str:
    return moment.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def env_value(path: Path, key: str) -> str | None:
    if not path.exists():
        return None
    for line in path.read_text().splitlines():
        if line.startswith(key + "="):
            return line.split("=", 1)[1]
    return None


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--api", default="https://localhost/api/v1")
    p.add_argument("--insecure", action="store_true", help="accept a self-signed certificate")
    p.add_argument("--admin", help="admin email (default: from out/loadtest.env)")
    p.add_argument("--admin-password", help="(default: from out/loadtest.env)")
    p.add_argument("--judge", default=str(HERE / "out" / "judge.json"),
                   help="setup_judge.py's output, for the defaults of the four below")
    p.add_argument("--domjudge-url", help="the judge's root, without /api, as the backend reaches it")
    p.add_argument("--dj-service-user",
                   help="a DOMjudge account that can read the contest's problems and scoreboard")
    p.add_argument("--dj-service-password")
    p.add_argument("--dj-contest", help="the DOMjudge contest id")
    p.add_argument("--roster", default=str(HERE.parent / "simulation" / "out" / "roster"
                                           / "roster.json"))
    p.add_argument("--users", type=int, default=200)
    p.add_argument("--starts-in", type=float, default=10,
                   help="minutes from now until the paper opens (sign-in opens an hour before)")
    p.add_argument("--duration", type=float, default=120, help="length of the paper, minutes")
    p.add_argument("--name", default=EXAM_NAME,
                   help="a new name makes a new examination, with a bell of its own")
    p.add_argument("--out", default=str(HERE / "out" / "exam.json"))
    args = p.parse_args()

    judge = json.loads(Path(args.judge).read_text()) if Path(args.judge).exists() else {}
    args.domjudge_url = args.domjudge_url or judge.get("url")
    args.dj_service_user = args.dj_service_user or judge.get("user")
    args.dj_service_password = args.dj_service_password or judge.get("password")
    args.dj_contest = args.dj_contest or judge.get("contest")
    if not all([args.domjudge_url, args.dj_service_user, args.dj_service_password,
                args.dj_contest]):
        raise SystemExit("No judge: run setup_judge.py first, or pass --domjudge-url, "
                         "--dj-service-user, --dj-service-password and --dj-contest.")

    env = HERE / "out" / "loadtest.env"
    admin = args.admin or env_value(env, "CPINTEL_ADMIN_EMAIL")
    password = args.admin_password or env_value(env, "CPINTEL_ADMIN_PASSWORD")
    if not admin or not password:
        raise SystemExit("No admin credentials: run stack.sh up first, or pass --admin.")

    roster = json.loads(Path(args.roster).read_text())["participants"][:args.users]
    if len(roster) < args.users:
        raise SystemExit(f"The roster has {len(roster)} people; make_roster.py --count "
                         f"{args.users} first.")

    c = Client(args.api, timeout=120, verify_tls=not args.insecure)
    login = c.post("/auth/login", body={"identifier": admin, "password": password})
    token = c.expect(login, "signing in as the admin")["accessToken"]

    def call(method: str, path: str, context: str, body=None):
        return c.expect(c.request(method, path, token=token, body=body), context)

    # 1-2. The classroom, on this judge.
    classroom = next((r for r in call("GET", "/admin/classrooms", "listing classrooms")
                      if r["domjudgeUrl"].rstrip("/") == args.domjudge_url.rstrip("/")), None)
    if classroom is None:
        classroom = call("POST", "/admin/classrooms", "creating the classroom", {
            "name": CLASSROOM_NAME, "domjudgeUrl": args.domjudge_url,
            "serviceUsername": args.dj_service_user,
            "servicePassword": args.dj_service_password})
    classroom_id = classroom["classroomId"]
    check = call("POST", f"/admin/classrooms/{classroom_id}/check", "checking the judge")
    print(f"classroom {classroom_id}: judge {'reachable' if check.get('reachable') else 'NOT reachable'}"
          f" {check.get('version') or ''} {check.get('message') or ''}".rstrip())
    if not check.get("reachable"):
        return 1

    # 3. The team and its roster, logins attached.
    team = next((g for g in call("GET", "/admin/groups", "listing teams")
                 if g["name"] == TEAM_NAME and g["classroomId"] == classroom_id), None)
    if team is None:
        team = call("POST", "/admin/groups", "creating the team",
                    {"name": TEAM_NAME, "classroomId": classroom_id})
    team_id = team["groupId"]
    lines = ["username,email,fullName,team,djUsername,djPassword"]
    lines += [f"{r['username']},{r['email']},{r['fullName']},{r['team']},"
              f"{r['djUsername']},{r['djPassword']}" for r in roster]
    imported = call("POST", f"/admin/groups/{team_id}/members/import", "importing the roster",
                    {"text": "\n".join(lines) + "\n", "dryRun": False})
    print(f"roster: {imported['total']} rows, {imported['toCreate']} accounts created, "
          f"{imported['alreadyMembers']} already members, DOMjudge logins "
          f"{imported['domjudgeOk']} attached / {imported['domjudgeFailed']} failed")
    if imported["domjudgeFailed"]:
        bad = [r for r in imported["rows"] if r.get("domjudgeStatus") == "FAILED"]
        for r in bad[:5]:
            print(f"  {r['username']}: {r.get('domjudgeMessage')}")
        print("Import accounts.tsv into the judge, then run this again.")
        return 1

    # 4. The examination.
    starts = datetime.now(timezone.utc) + timedelta(minutes=args.starts_in)
    ends = starts + timedelta(minutes=args.duration)
    exam = next((e for e in call("GET", "/admin/events?kind=EXAM", "listing examinations")
                 if e.get("name") == args.name and e.get("judgeContestId") == args.dj_contest),
                None)
    if exam is None:
        detail = call("POST", "/admin/events", "creating the examination", {
            "kind": "EXAM", "platform": "DOMJUDGE", "externalId": args.dj_contest,
            "name": args.name, "startsAt": iso(starts), "endsAt": iso(ends),
            "lockdownRequired": True, "awayThresholdSeconds": 60,
            "teamIds": [team_id], "classroomId": classroom_id})
        exam = detail["event"]
    exam_id = exam["eventId"]
    # A reused examination keeps the window it was made with; report that one, not ours.
    starts_at = exam.get("startsAt") or iso(starts)
    ends_at = exam.get("endsAt") or iso(ends)

    problems = call("GET", f"/admin/events/{exam_id}/judge-problems", "reading the problems")
    call("PUT", f"/admin/events/{exam_id}/problems", "setting the problems", {"problems": [
        {"label": pr["label"], "title": pr["title"], "externalId": pr["externalId"],
         "ordering": i, "points": 1.0} for i, pr in enumerate(problems)]})
    call("PUT", f"/admin/events/{exam_id}/lifecycle", "publishing", {"lifecycle": "SCHEDULED"})
    print(f"examination {exam_id} on {exam['externalId']}: {len(problems)} problems, "
          f"{starts_at} to {ends_at}")

    # 5. The passwords.
    room = call("POST", f"/admin/events/{exam_id}/passwords/exam", "the room password")
    codes = call("POST", f"/admin/events/{exam_id}/passwords/candidates",
                 "issuing candidate passwords")
    by_user = {c["username"]: c for c in codes}
    candidates = [{"username": r["username"], "userId": by_user[r["username"]]["userId"],
                   "passcode": by_user[r["username"]]["code"]}
                  for r in roster if r["username"] in by_user]
    print(f"{len(candidates)} candidate passwords issued")

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({
        "api": args.api, "examId": exam_id, "contestRef": exam["externalId"],
        "startsAt": starts_at, "endsAt": ends_at,
        "roomPassword": room["password"], "problems": [pr["label"] for pr in problems],
        "candidates": candidates}, indent=2))
    out.chmod(0o600)
    print(f"Wrote {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
