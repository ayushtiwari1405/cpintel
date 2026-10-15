#!/usr/bin/env python3
"""
Does a skill model predict what a student will solve? Compares several, on real histories.

    ./scripts/analytics-eval/fetch.py --users 300     # once
    ./scripts/analytics-eval/evaluate.py

WHAT IS PREDICTED

Problems in a user's later rated contests: solved during the round, or not. Contests, not
practice, because in a round the user meets every problem and an unsolved one is a real failure.
A practice history only holds problems the user chose to submit to, and over 90% of those end
accepted for nearly everyone — people submit once they believe they have it — so "solved out of
attempted" in practice is close to a constant. That is also how Codeforces defines a problem's
rating: the rating at which a contestant solves it in the round half the time.

Each user's rated contests are split in time: the earlier 75% are history, the rest are
predicted. Users are split in two by a hash of their handle. Every free parameter and every
model's calibration is fitted on one half; scores are reported on the other.

MODELS

Every model is "the user's rating going into the round, plus an offset for the problem's skills",
on the Elo curve, then calibrated. Offsets come from the earlier rounds, each with a normal prior,
older rounds weighted down; a problem's offset is the mean over its skills. The unit of skill is
what differs:

  rating           no offset. The baseline every skill model has to beat.
  mastery+rating   the old 0-100 mastery score (practice history) beside the rating.
  tag              per Codeforces tag.
  topic            per roll-up topic of the roadmap (21).
  node             per roadmap node (140) — what the product rates.
  node-pooled      per node, each drawn towards its topic's offset instead of towards zero.
  node-tried       per node, a problem tried in the round but not solved counted as part
                   of a solve rather than as nothing.

Nodes and topics come from the backend's own rules (AttributionExport.java), so this scores
exactly what the product computes. Each model's parameters are chosen on the tuning half.

The difference in log-loss against "rating" is reported with a bootstrap interval over users,
because the effects being looked for are small and a difference without an interval would not
say whether there is one.

Needs numpy.
"""
import bisect
import hashlib
import json
import math
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
USERS = HERE / "data" / "users"
PROBLEMS = HERE / "data" / "problems.json"
K = math.log(10) / 400  # Elo: 400 points is a factor of ten in odds
DAY = 86400

MIN_TRAIN_CONTESTS, MIN_TEST_CONTESTS = 6, 2
TRAIN_SHARE = 0.75


# ── data ───────────────────────────────────────────────────────────────────

def load_problems():
    by_contest = defaultdict(list)
    for p in json.loads(PROBLEMS.read_text()):
        if "rating" in p and p.get("tags") and "contestId" in p:
            by_contest[p["contestId"]].append(
                (p["index"], p["rating"], tuple(sorted(set(p["tags"])))))
    return by_contest


# Accounts made since 2020 show 0 going into their first round, while Codeforces rates them as
# 1400 underneath; the shown rating catches up over six rounds by these bonuses. Until it has,
# the shown rating understates the person by what is still to be paid.
NEW_ACCOUNT_BONUSES = (500, 350, 250, 150, 100, 50)


def true_ratings(history):
    """For each round, (rating going in, rating coming out), corrected for the hidden start."""
    new_system = bool(history) and history[0]["oldRating"] == 0
    out = []
    for k, h in enumerate(history):
        def unpaid(n):
            return 1400 - sum(NEW_ACCOUNT_BONUSES[:n]) if new_system and n < 6 else 0
        out.append((h["oldRating"] + unpaid(k), h["newRating"] + unpaid(k + 1)))
    return out


def rating_at(history, t):
    """Rating going into time t: the rating after the last round that finished before it."""
    i = bisect.bisect_left([h["ratingUpdateTimeSeconds"] for h in history], t)
    return true_ratings(history)[i - 1][1] if i > 0 else None


def practice_problems(submissions, contest_keys, cut):
    """Problems first tried before the cut, outside the user's own rated rounds."""
    seen = {}
    for s in sorted(submissions, key=lambda s: s["creationTimeSeconds"]):
        p = s["problem"]
        if s["creationTimeSeconds"] >= cut or "rating" not in p or not p.get("tags"):
            continue
        key = (p.get("contestId"), p["index"])
        if key in contest_keys:
            continue
        e = seen.setdefault(key, {"d": p["rating"], "tags": tuple(sorted(set(p["tags"]))),
                                  "t": s["creationTimeSeconds"], "y": 0.0})
        if s.get("verdict") == "OK":
            e["y"] = 1.0
    return list(seen.values())


def load_nodes():
    """(contestId, index) -> (nodes, topics), by the backend's own attribution rules."""
    tsv_in, tsv_out = HERE / "data" / "problems.tsv", HERE / "data" / "nodes.tsv"
    if not tsv_out.exists() or tsv_out.stat().st_mtime < PROBLEMS.stat().st_mtime:
        with open(tsv_in, "w") as f:
            for p in json.loads(PROBLEMS.read_text()):
                if "rating" in p and p.get("tags") and "contestId" in p:
                    f.write(f"{p['contestId']}\t{p['index']}\t{p['rating']}\t{'|'.join(p['tags'])}\n")
        classes = ROOT / "backend" / "target" / "classes"
        if not (classes / "com" / "cpintel" / "roadmap" / "SkillAttribution.class").exists():
            sys.exit("Build the backend first (cd backend && ./mvnw -q compile).")
        subprocess.run(["java", "-cp", str(classes), str(HERE / "AttributionExport.java"),
                        str(tsv_in), str(tsv_out)], check=True)
    out = {}
    for line in tsv_out.read_text().splitlines():
        cid, idx, nodes, topics = line.split("\t")
        out[(int(cid), idx)] = (tuple(n for n in nodes.split("|") if n),
                                tuple(sorted({t for t in topics.split("|") if t})))
    return out


def load():
    by_contest = load_problems()
    attribution = load_nodes()
    users = []
    for path in sorted(USERS.glob("*.json")):
        u = json.loads(path.read_text())
        history = sorted(u["rating"], key=lambda h: h["ratingUpdateTimeSeconds"])
        live = [s for s in u["submissions"]
                if s.get("author", {}).get("participantType") == "CONTESTANT"]
        solved_live = {(s["problem"].get("contestId"), s["problem"]["index"])
                       for s in live if s.get("verdict") == "OK"}
        tried_live = {(s["problem"].get("contestId"), s["problem"]["index"])
                      for s in live if s.get("verdict") not in (None, "OK", "COMPILATION_ERROR")}
        rounds = []
        for h, (before, _) in zip(history, true_ratings(history)):
            probs = by_contest.get(h["contestId"])
            if not probs or before <= 0:
                continue
            obs = []
            for idx, d, tags in probs:
                key = (h["contestId"], idx)
                nodes, topics = attribution.get(key, ((), ()))
                obs.append({"d": d, "tags": tags, "nodes": nodes, "topics": topics,
                            "y": 1.0 if key in solved_live else 0.0,
                            "tried": key in tried_live and key not in solved_live})
            rounds.append({"t": h["ratingUpdateTimeSeconds"], "R": before, "obs": obs})
        # A first round on the old system went in at a flat 1500 placeholder: no measurement yet.
        if history and history[0]["oldRating"] != 0:
            rounds = rounds[1:]
        n_train = int(len(rounds) * TRAIN_SHARE)
        if n_train < MIN_TRAIN_CONTESTS or len(rounds) - n_train < MIN_TEST_CONTESTS:
            continue
        cut = rounds[n_train]["t"]
        contest_keys = {(h["contestId"], idx) for h in history
                        for idx, _, _ in by_contest.get(h["contestId"], [])}
        counts = defaultdict(lambda: [0, 0])
        for e in practice_problems(u["submissions"], contest_keys, cut):
            for t in e["tags"]:
                counts[t][0] += int(e["y"])
                counts[t][1] += 1
        users.append({
            "handle": u["handle"], "cut": cut,
            "train": [dict(o, R=r["R"], t=r["t"]) for r in rounds[:n_train] for o in r["obs"]],
            "test": [dict(o, R=r["R"]) for r in rounds[n_train:] for o in r["obs"]],
            "mastery": {t: mastery(s, a) for t, (s, a) in counts.items()},
            "tune": int(hashlib.sha256(u["handle"].encode()).hexdigest(), 16) % 2 == 0,
        })
    return users


# ── estimation ─────────────────────────────────────────────────────────────

def map_offset(base, d, y, w, tau, mu=0.0):
    """Most likely offset in P = logistic(K (base + delta - d)), with a N(mu, tau) prior."""
    delta = mu
    for _ in range(30):
        p = 1 / (1 + np.exp(np.clip(-K * (base + delta - d), -50, 50)))
        grad = np.sum(w * K * (y - p)) - (delta - mu) / tau ** 2
        hess = -np.sum(w * K * K * p * (1 - p)) - 1 / tau ** 2
        step = grad / hess
        delta -= step
        if abs(step) < 0.01:
            break
    return delta


def offsets(obs, cut, unit, half_life, tau, credit=0.0, prior=None):
    """Offset per skill of this unit ('tags', 'topics', 'nodes') from the earlier rounds."""
    if not obs:
        return {}
    base = np.array([o["R"] for o in obs], float)
    d = np.array([o["d"] for o in obs], float)
    y = np.array([o["y"] if not o["tried"] else max(o["y"], credit) for o in obs], float)
    age = np.array([(cut - o["t"]) / DAY for o in obs], float)
    w = 0.5 ** (age / half_life)
    groups = defaultdict(list)
    for i, o in enumerate(obs):
        for k in o[unit]:
            groups[k].append(i)
    out = {}
    for k, idx in groups.items():
        idx = np.array(idx)
        mu = prior(k) if prior else 0.0
        out[k] = map_offset(base[idx], d[idx], y[idx], w[idx], tau, mu)
    return out


def mastery(solved, attempted):
    acc = min(100.0, solved / attempted * 100) if attempted else 0.0
    vol = min(100.0, math.log1p(solved) / math.log1p(1500) * 100) if solved > 0 else 0.0
    return acc * 0.55 + vol * 0.45


TOPIC_OF = {}  # node -> roll-up topic, filled from the attribution as it is read


def skill_offsets(user, model, p):
    tr, cut = user["train"], user["cut"]
    if model == "tag":
        return "tags", offsets(tr, cut, "tags", p["hl"], p["tau"])
    if model == "topic":
        return "topics", offsets(tr, cut, "topics", p["hl"], p["tau"])
    if model == "node":
        return "nodes", offsets(tr, cut, "nodes", p["hl"], p["tau"])
    if model == "node-tried":
        return "nodes", offsets(tr, cut, "nodes", p["hl"], p["tau"], credit=p["credit"])
    if model == "node-pooled":
        topic = offsets(tr, cut, "topics", p["hl"], p["tau"])
        return "nodes", offsets(tr, cut, "nodes", p["hl"], p["tau_n"],
                                prior=lambda n: topic.get(TOPIC_OF.get(n), 0.0))
    raise ValueError(model)


def rows_for(user, model, p):
    rows = []
    unit, table = (None, {}) if model in ("rating", "mastery+rating") else skill_offsets(user, model, p)
    for o in user["test"]:
        keys = o[unit] if unit else ()
        off = float(np.mean([table.get(k, 0.0) for k in keys])) if keys else 0.0
        m = (float(np.mean([user["mastery"].get(t, 0.0) for t in o["tags"]]))
             if model == "mastery+rating" else 0.0)
        rows.append({"y": o["y"], "gap": o["R"] - o["d"], "off": off, "mastery": m})
    return rows


def design(rows, model):
    if model == "mastery+rating":
        return np.array([[1, K * r["gap"], r["mastery"] / 100] for r in rows])
    return np.array([[1, K * (r["gap"] + r["off"])] for r in rows])


def fit_logistic(X, y, l2=1e-3):
    beta = np.zeros(X.shape[1])
    for _ in range(60):
        p = 1 / (1 + np.exp(-X @ beta))
        g = X.T @ (y - p) - l2 * beta
        H = -(X.T * (p * (1 - p))) @ X - l2 * np.eye(len(beta))
        step = np.linalg.solve(H, g)
        beta -= step
        if np.max(np.abs(step)) < 1e-9:
            break
    return beta


def predict(X, beta):
    return np.clip(1 / (1 + np.exp(-X @ beta)), 1e-6, 1 - 1e-6)


def logloss_each(y, p):
    return -(y * np.log(p) + (1 - y) * np.log(1 - p))


def auc(y, p):
    order = np.argsort(p)
    ranks = np.empty(len(p))
    ranks[order] = np.arange(1, len(p) + 1)
    pos = y == 1
    n1, n0 = pos.sum(), (~pos).sum()
    return float((ranks[pos].sum() - n1 * (n1 + 1) / 2) / (n1 * n0))


# ── run ────────────────────────────────────────────────────────────────────

GRIDS = {
    "rating": [{}],
    "mastery+rating": [{}],
    "tag": [{"hl": hl, "tau": t} for hl in (180, 365) for t in (50, 100, 200)],
    "topic": [{"hl": hl, "tau": t} for hl in (180, 365) for t in (50, 100, 200)],
    "node": [{"hl": hl, "tau": t} for hl in (180, 365) for t in (50, 100, 200)],
    "node-pooled": [{"hl": hl, "tau": t, "tau_n": tn} for hl in (180, 365)
                    for t in (50, 100) for tn in (25, 50, 100)],
    "node-tried": [{"hl": hl, "tau": t, "credit": c} for hl in (180,)
                   for t in (50, 100, 200) for c in (0.25, 0.5)],
}
MODELS = list(GRIDS)


def score(users, model, p, fit_on, judge_on):
    fit = [r for u in users if fit_on(u) for r in rows_for(u, model, p)]
    judged = [(i, r) for i, u in enumerate(users) if judge_on(u) for r in rows_for(u, model, p)]
    rows = [r for _, r in judged]
    y = np.array([r["y"] for r in rows])
    p_hat = predict(design(rows, model),
                    fit_logistic(design(fit, model), np.array([r["y"] for r in fit])))
    return logloss_each(y, p_hat), p_hat, y, np.array([i for i, _ in judged])


def main():
    users = load()
    if len(users) < 30:
        sys.exit(f"Only {len(users)} usable users; run fetch.py first.")
    for line in (HERE / "data" / "nodes.tsv").read_text().splitlines():
        _, _, nodes, topics = line.split("\t")
        for n, t in zip(nodes.split("|"), topics.split("|")):
            if n:
                TOPIC_OF[n] = t
    tune = [u for u in users if u["tune"]]
    half_a = lambda u: int(hashlib.sha256(u["handle"].encode()).hexdigest(), 16) % 4 == 0

    chosen = {}
    for m in MODELS:
        best = None
        for p in GRIDS[m]:
            ll = float(np.mean(score(tune, m, p, half_a, lambda u: not half_a(u))[0]))
            if best is None or ll < best[0]:
                best = (ll, p)
        chosen[m] = best[1]

    results = {m: score(users, m, chosen[m], lambda u: u["tune"], lambda u: not u["tune"])
               for m in MODELS}
    _, _, y, owner = results["rating"]
    scored = np.unique(owner)
    print(f"{len(users)} users with enough rated rounds: {len(tune)} tuning, {len(scored)} scored")
    print(f"{len(y)} scored contest problems, {int(y.sum())} solved in the round "
          f"({100 * y.mean():.1f}%)\n")

    rng = np.random.default_rng(20261015)
    picks = [rng.choice(scored, len(scored)) for _ in range(2000)]
    masks = [np.concatenate([np.flatnonzero(owner == i) for i in pick]) for pick in picks]

    def interval(diff):
        boots = [diff[m].mean() for m in masks]
        lo, hi = np.percentile(boots, [2.5, 97.5])
        return f"{1000 * diff.mean():+7.2f} [{1000 * lo:+6.2f}, {1000 * hi:+6.2f}]"

    print(f"{'model':15} {'log-loss':>9} {'AUC':>6}  {'vs rating (x1000, 95%)':>27}  "
          f"{'vs node (x1000, 95%)':>27}  chosen")
    for m in MODELS:
        ll, p_hat, _, _ = results[m]
        print(f"{m:15} {ll.mean():9.4f} {auc(y, p_hat):6.3f}  "
              f"{interval(ll - results['rating'][0]):>27}  "
              f"{interval(ll - results['node'][0]):>27}  {chosen[m]}")
    print("\nNegative is better. An interval that crosses 0 is no evidence either way.")


if __name__ == "__main__":
    main()
