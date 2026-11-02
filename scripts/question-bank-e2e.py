#!/usr/bin/env python3
"""The question bank, end to end, against a running CPIntel and a real DOMjudge.

    python3 scripts/question-bank-e2e.py --cpintel http://localhost:5173 \
        --superadmin USER --superadmin-password-file FILE \
        --judge http://127.0.0.1:12345 --judge-admin-password-file FILE

What it does, checking each step:
  1. Creates a classroom on the judge, with the judge's admin as its service account.
  2. Imports the bank's own package template, and checks its model solution.
  3. Creates a draft examination and chooses the question for it.
  4. Publishes, then reads the judge directly: the contest exists, problem A is ours, labelled,
     with every test; and the examination's problem list points at it.
  5. Lets the examination start, corrects the question (one more secret test), checks it again,
     publishes the correction with a notice, and reads the judge again: same problem, one more
     test; the notice is recorded.

It writes to both systems and cleans up neither: DOMjudge's API cannot delete a contest. Point it
at a throwaway judge (scripts/loadtest/stack.sh brings one up) and a development CPIntel.
Needs: requests.
"""
import argparse
import sys
import time
from datetime import datetime, timedelta, timezone

import requests

STEP = [0]


def say(message):
    STEP[0] += 1
    print(f"[{STEP[0]:>2}] {message}", flush=True)


def fail(message):
    print(f"FAILED: {message}", file=sys.stderr, flush=True)
    sys.exit(1)


def expect(condition, message):
    if not condition:
        fail(message)


class Api:
    def __init__(self, base, user, password):
        self.base = base.rstrip("/") + "/api/v1"
        self.s = requests.Session()
        r = self.s.post(self.base + "/auth/login", json={"identifier": user, "password": password}, timeout=30)
        expect(r.ok, f"sign-in as {user}: HTTP {r.status_code} {r.text[:200]}")
        self.s.headers["Authorization"] = "Bearer " + r.json()["data"]["accessToken"]

    def call(self, method, path, ok=(200, 201, 202), **kw):
        r = self.s.request(method, self.base + path, timeout=300, **kw)
        if r.status_code not in ok:
            fail(f"{method} {path}: HTTP {r.status_code} {r.text[:400]}")
        if r.headers.get("Content-Type", "").startswith("application/json"):
            return r.json().get("data")
        return r.content


def wait_for_check(api, qid):
    for _ in range(120):
        check = api.call("GET", f"/question-bank/questions/{qid}/check")
        if check and check["status"] not in ("QUEUED", "RUNNING"):
            return check
        time.sleep(1)
    fail("the check did not finish within two minutes")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--cpintel", required=True, help="CPIntel's address, e.g. http://localhost:5173")
    p.add_argument("--superadmin", required=True)
    p.add_argument("--superadmin-password-file", required=True)
    p.add_argument("--judge", required=True, help="the judge as CPIntel's backend reaches it")
    p.add_argument("--judge-from-here", help="the judge as this machine reaches it, if different")
    p.add_argument("--judge-admin", default="admin")
    p.add_argument("--judge-admin-password-file", required=True)
    a = p.parse_args()
    judge_password = open(a.judge_admin_password_file).read().strip()
    api = Api(a.cpintel, a.superadmin, open(a.superadmin_password_file).read().strip())
    judge = requests.Session()
    judge.auth = (a.judge_admin, judge_password)
    judge_api = (a.judge_from_here or a.judge).rstrip("/") + "/api/v4"
    tag = datetime.now().strftime("%m%d%H%M%S")

    say("classroom on the judge, with its admin as the service account")
    # A judge belongs to one classroom: reuse the one already on it, from an earlier run.
    account = {"serviceUsername": a.judge_admin, "servicePassword": judge_password}
    existing = [c for c in api.call("GET", "/admin/classrooms")
                if (c["domjudgeUrl"] or "").rstrip("/") == a.judge.rstrip("/")]
    if existing:
        room = existing[0]
        api.call("PUT", f"/admin/classrooms/{room['classroomId']}", json={
            "name": room["name"], "description": room["description"], "domjudgeUrl": room["domjudgeUrl"], **account})
    else:
        room = api.call("POST", "/admin/classrooms", json={
            "name": f"Question bank e2e {tag}", "domjudgeUrl": a.judge, **account})
    room_id = room["classroomId"]

    say("the package template, imported and checked")
    template = api.call("GET", "/question-bank/package-template.zip")
    imported = api.call("POST", f"/question-bank/classrooms/{room_id}/questions/import", data=template,
                        headers={"Content-Type": "application/zip"}, params={"fileName": "template.zip"})
    expect(imported["warnings"] == [], f"the template imported with warnings: {imported['warnings']}")
    qid = imported["question"]["questionId"]
    api.call("POST", f"/question-bank/questions/{qid}/check", json={"generateMissing": False})
    check = wait_for_check(api, qid)
    expect(check["status"] == "PASSED", f"check: {check['status']} {check['summary']}")

    say("a draft examination, starting in a few seconds, with the question chosen")
    contest = f"qbe2e{tag}"
    starts = datetime.now(timezone.utc) + timedelta(seconds=20)
    exam = api.call("POST", "/admin/events", json={
        "kind": "EXAM", "platform": "DOMJUDGE", "externalId": contest, "name": f"Bank e2e {tag}",
        "classroomId": room_id, "startsAt": starts.isoformat().replace("+00:00", "Z"),
        "endsAt": (starts + timedelta(hours=2)).isoformat().replace("+00:00", "Z")})
    eid = exam["event"]["eventId"]
    api.call("PUT", f"/admin/events/{eid}/bank/questions", json={"questionIds": [qid]})

    say("publish")
    state = api.call("POST", f"/admin/events/{eid}/bank/publish")
    expect(state["publication"]["status"] == "PUBLISHED", f"publication: {state['publication']}")
    pid = f"cpi-e{eid}-q{qid}"
    expect(state["chosen"][0]["judgeProblemId"] == pid and state["chosen"][0]["label"] == "A",
           f"chosen after publishing: {state['chosen']}")

    say("the judge has the contest and problem A, with every test")
    r = judge.get(f"{judge_api}/contests/{contest}", timeout=30)
    expect(r.ok, f"contest {contest} on the judge: HTTP {r.status_code}")
    problems = judge.get(f"{judge_api}/contests/{contest}/problems", timeout=30).json()
    ours = [x for x in problems if x["id"] == pid]
    expect(len(ours) == 1, f"problem {pid} on the judge: {problems}")
    expect(ours[0]["label"] == "A", f"label: {ours[0]['label']}")
    expect(ours[0]["test_data_count"] == 5, f"tests on the judge: {ours[0]['test_data_count']}")
    expect(ours[0]["time_limit"] == 1.0, f"time limit on the judge: {ours[0]['time_limit']}")
    detail = api.call("GET", f"/admin/events/{eid}")
    expect([(x["label"], x["externalId"]) for x in detail["problems"]] == [("A", pid)],
           f"the examination's problems: {detail['problems']}")

    say("the examination starts")
    time.sleep(max(0, (starts - datetime.now(timezone.utc)).total_seconds()) + 2)
    api.call("PUT", f"/admin/events/{eid}/lifecycle", json={"lifecycle": "SCHEDULED"})
    state = api.call("GET", f"/admin/events/{eid}/bank")
    expect(not state["editable"], "the examination should count as started")

    say("a correction during the paper: one more secret test, checked again")
    question = api.call("GET", f"/question-bank/questions/{qid}")
    api.call("POST", f"/question-bank/questions/{qid}/tests",
             files=[("files", ("extra.in", b"2\n40 2\n")), ("files", ("extra.ans", b"42\n"))],
             data={"sample": "false", "version": str(question["version"])})
    api.call("POST", f"/question-bank/questions/{qid}/check", json={"generateMissing": False})
    check = wait_for_check(api, qid)
    expect(check["status"] == "PASSED", f"check after the correction: {check['status']} {check['summary']}")

    say("publish the correction, with a notice")
    notice = "Question A has one more test. Your submissions to it will be judged again."
    state = api.call("POST", f"/admin/events/{eid}/bank/questions/{qid}/republish",
                     json={"confirm": True, "notice": notice})
    expect(state["chosen"][0]["publishedVersion"] == check["version"], f"published version: {state['chosen'][0]}")
    expect([x["message"] for x in state["announcements"]] == [notice], f"notices: {state['announcements']}")

    say("the judge has the same problem A, with the extra test")
    problems = judge.get(f"{judge_api}/contests/{contest}/problems", timeout=30).json()
    ours = [x for x in problems if x["id"] == pid]
    expect(len(ours) == 1 and ours[0]["label"] == "A", f"problem after the correction: {ours}")
    expect(ours[0]["test_data_count"] == 6, f"tests after the correction: {ours[0]['test_data_count']}")
    expect(len(problems) == 1, f"the contest should still have one problem: {[x['id'] for x in problems]}")

    print(f"PASSED: classroom {room_id}, question {qid}, examination {eid}, judge contest {contest}")


if __name__ == "__main__":
    main()
