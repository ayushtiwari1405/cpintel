#!/usr/bin/env python3
"""
Downloads a sample of real Codeforces histories, for evaluating the skill models offline.

    ./scripts/analytics-eval/fetch.py --users 300
    ./scripts/analytics-eval/fetch.py --users 600     # grows an existing sample to 600

Users are sampled from the rating changes of recent rated rounds, stratified by rating band so
the sample is not all newcomers (who dominate any round's participant list). For each one it
saves the full submission history (user.status) and rating history (user.rating), and once,
every problem's contest, rating and tags (problemset.problems).

Paced at one request every two seconds, which is what Codeforces asks for; 300 users take about
twenty minutes. Already-downloaded users are skipped, so an interrupted run can be resumed.
Everything lands in scripts/analytics-eval/data/, which is gitignored.
"""
import argparse
import json
import random
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

API = "https://codeforces.com/api/"
DATA = Path(__file__).resolve().parent / "data"
PACE_S = 2.0

# Bands by rating at the time of the round. Upper bounds are exclusive.
BANDS = [(0, 1200), (1200, 1400), (1400, 1600), (1600, 1900), (1900, 2100), (2100, 4000)]

_last = 0.0


def call(method, **params):
    """One API call, paced, with a few retries for the API's occasional 'Call limit exceeded'."""
    global _last
    url = API + method + "?" + urllib.parse.urlencode(params)
    for attempt in range(5):
        wait = _last + PACE_S - time.time()
        if wait > 0:
            time.sleep(wait)
        _last = time.time()
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                body = json.load(r)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
            print(f"  {method}: {e}; retrying", file=sys.stderr)
            time.sleep(5 * (attempt + 1))
            continue
        if body.get("status") == "OK":
            return body["result"]
        comment = body.get("comment", "")
        if "limit" in comment.lower():
            time.sleep(10)
            continue
        raise RuntimeError(f"{method}: {comment}")
    raise RuntimeError(f"{method}: gave up after retries")


def sample_handles(per_band, contests, exclude=frozenset(), seed=20261015):
    pools = {b: set() for b in BANDS}
    for cid in contests:
        try:
            changes = call("contest.ratingChanges", contestId=cid)
        except RuntimeError as e:
            print(f"  skipping contest {cid}: {e}", file=sys.stderr)
            continue
        for ch in changes:
            if ch["handle"] in exclude:
                continue
            for lo, hi in BANDS:
                if lo <= ch["oldRating"] < hi:
                    pools[(lo, hi)].add(ch["handle"])
        if all(len(p) >= per_band * 3 for p in pools.values()):
            break
    rng = random.Random(seed)
    chosen = []
    for band, pool in pools.items():
        pick = rng.sample(sorted(pool), min(per_band, len(pool)))
        print(f"  band {band}: {len(pick)} of {len(pool)}")
        chosen += pick
    return chosen


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--users", type=int, default=300)
    args = ap.parse_args()

    (DATA / "users").mkdir(parents=True, exist_ok=True)
    handles_file = DATA / "handles.json"

    handles = json.loads(handles_file.read_text()) if handles_file.exists() else []
    if len(handles) < args.users:
        # Growing an existing sample: new people, drawn the same way, from older rounds too.
        contests = [c["id"] for c in call("contest.list", gym="false")
                    if c["phase"] == "FINISHED" and "Div." in c["name"]][:120]
        more = sample_handles((args.users - len(handles)) // len(BANDS), contests,
                              exclude=frozenset(handles), seed=20261015 + len(handles))
        handles += more
        handles_file.write_text(json.dumps(handles))

    # Every problem's contest, rating and tags, so a round's unsolved problems can be counted
    # as the failures they are; a user's own submissions only mention problems they tried.
    problems_file = DATA / "problems.json"
    if not problems_file.exists():
        problems_file.write_text(json.dumps(call("problemset.problems")["problems"]))

    for i, handle in enumerate(handles, 1):
        out = DATA / "users" / f"{handle}.json"
        if out.exists():
            continue
        try:
            subs = call("user.status", handle=handle)
            rating = call("user.rating", handle=handle)
        except RuntimeError as e:
            print(f"[{i}/{len(handles)}] {handle}: {e}", file=sys.stderr)
            continue
        out.write_text(json.dumps({"handle": handle, "submissions": subs, "rating": rating}))
        print(f"[{i}/{len(handles)}] {handle}: {len(subs)} submissions, {len(rating)} rounds")


if __name__ == "__main__":
    main()
