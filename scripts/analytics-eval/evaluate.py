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

Every model is "the user's rating going into the round, plus an offset for the problem's topics",
on the Elo curve, then calibrated:

  rating           no offset. The baseline every topic model has to beat.
  mastery+rating   CPIntel's current mastery score (0.55 accuracy + 0.45 log-volume, per tag,
                   from the practice history) given to the calibration alongside the rating.
  contest-topic    per-tag offsets estimated from the earlier contests: which tags this person
                   does better or worse on than their rating says. Thin evidence is drawn to 0.
  practice-topic   per-tag offsets from the practice history, relative to the user's own
                   practice average, so the practice inflation cancels and only "stronger at DP
                   than at graphs" is kept.
  combined         contest-topic + lambda x practice-topic.

The difference in log-loss against "rating" is reported with a bootstrap interval over users,
because the effects being looked for are small and a difference without an interval would not
say whether there is one.

Needs numpy.
"""
import bisect
import hashlib
import json
import math
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
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


def load():
    by_contest = load_problems()
    users = []
    for path in sorted(USERS.glob("*.json")):
        u = json.loads(path.read_text())
        history = sorted(u["rating"], key=lambda h: h["ratingUpdateTimeSeconds"])
        solved_live = {(s["problem"].get("contestId"), s["problem"]["index"])
                       for s in u["submissions"]
                       if s.get("verdict") == "OK"
                       and s.get("author", {}).get("participantType") == "CONTESTANT"}
        rounds = []
        for h, (before, _) in zip(history, true_ratings(history)):
            probs = by_contest.get(h["contestId"])
            if not probs or before <= 0:
                continue
            rounds.append({
                "t": h["ratingUpdateTimeSeconds"], "R": before,
                "obs": [{"d": d, "tags": tags,
                         "y": 1.0 if (h["contestId"], idx) in solved_live else 0.0}
                        for idx, d, tags in probs],
            })
        # A first round on the old system went in at a flat 1500 placeholder: no measurement yet.
        if history and history[0]["oldRating"] != 0:
            rounds = rounds[1:]
        n_train = int(len(rounds) * TRAIN_SHARE)
        if n_train < MIN_TRAIN_CONTESTS or len(rounds) - n_train < MIN_TEST_CONTESTS:
            continue
        cut = rounds[n_train]["t"]
        contest_keys = {(h["contestId"], idx) for h in history
                        for idx, _, _ in by_contest.get(h["contestId"], [])}
        practice = []
        for e in practice_problems(u["submissions"], contest_keys, cut):
            R = rating_at(history, e["t"])
            if R is not None:
                practice.append(dict(e, R=R))
        users.append({
            "handle": u["handle"], "cut": cut,
            "train": [dict(o, R=r["R"], t=r["t"]) for r in rounds[:n_train] for o in r["obs"]],
            "test": [dict(o, R=r["R"]) for r in rounds[n_train:] for o in r["obs"]],
            "practice": practice,
            "tune": int(hashlib.sha256(u["handle"].encode()).hexdigest(), 16) % 2 == 0,
        })
    return users


# ── estimation ─────────────────────────────────────────────────────────────

def map_offset(base, d, y, w, tau):
    """Most likely offset delta in P = logistic(K (base + delta - d)), with a N(0, tau) prior."""
    delta = 0.0
    for _ in range(30):
        p = 1 / (1 + np.exp(np.clip(-K * (base + delta - d), -50, 50)))
        grad = np.sum(w * K * (y - p)) - delta / tau ** 2
        hess = -np.sum(w * K * K * p * (1 - p)) - 1 / tau ** 2
        step = grad / hess
        delta -= step
        if abs(step) < 0.01:
            break
    return delta


def tag_offsets(obs, cut, half_life, tau, relative=False):
    if not obs:
        return {}
    base = np.array([o["R"] for o in obs], float)
    d = np.array([o["d"] for o in obs], float)
    y = np.array([o["y"] for o in obs], float)
    age = np.array([(cut - o["t"]) / DAY for o in obs], float)
    w = np.ones_like(d) if half_life is None else 0.5 ** (age / half_life)

    # The user's practice average, which the per-tag offsets are measured from. Its prior is wide
    # but finite: practice runs at about 95% solved, and with no prior at all the estimate runs
    # off towards infinity for someone who has never failed a practice problem.
    shift = map_offset(base, d, y, w, 400.0) if relative else 0.0
    by_tag = defaultdict(list)
    for i, o in enumerate(obs):
        for t in o["tags"]:
            by_tag[t].append(i)
    out = {}
    for t, idx in by_tag.items():
        idx = np.array(idx)
        out[t] = map_offset(base[idx] + shift, d[idx], y[idx], w[idx], tau)
    return out


def mastery(solved, attempted):
    acc = min(100.0, solved / attempted * 100) if attempted else 0.0
    vol = min(100.0, math.log1p(solved) / math.log1p(1500) * 100) if solved > 0 else 0.0
    return acc * 0.55 + vol * 0.45


def rows_for(user, half_life, tau, lam):
    contest = tag_offsets(user["train"], user["cut"], half_life, tau)
    practice = tag_offsets(user["practice"], user["cut"], half_life, tau, relative=True)

    counts = defaultdict(lambda: [0, 0])
    for e in user["practice"]:
        for t in e["tags"]:
            counts[t][0] += int(e["y"])
            counts[t][1] += 1
    tag_mastery = {t: mastery(s, a) for t, (s, a) in counts.items()}

    def mean(table, tags):
        return float(np.mean([table.get(t, 0.0) for t in tags]))

    rows = []
    for o in user["test"]:
        c, p = mean(contest, o["tags"]), mean(practice, o["tags"])
        rows.append({
            "y": o["y"], "gap": o["R"] - o["d"],
            "mastery": mean(tag_mastery, o["tags"]),
            "off": {"rating": 0.0, "contest-topic": c, "practice-topic": p,
                    "combined": c + lam * p},
        })
    return rows


MODELS = ["rating", "mastery+rating", "contest-topic", "practice-topic", "combined"]


def design(rows, model):
    if model == "mastery+rating":
        return np.array([[1, K * r["gap"], r["mastery"] / 100] for r in rows])
    return np.array([[1, K * (r["gap"] + r["off"][model])] for r in rows])


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

def score(users, half_life, tau, lam, fit_on, judge_on):
    fit_rows = [r for u in users if fit_on(u) for r in rows_for(u, half_life, tau, lam)]
    judge = [(i, r) for i, u in enumerate(users) if judge_on(u)
             for r in rows_for(u, half_life, tau, lam)]
    owner = np.array([i for i, _ in judge])
    rows = [r for _, r in judge]
    y_fit = np.array([r["y"] for r in fit_rows])
    y = np.array([r["y"] for r in rows])
    result = {}
    for m in MODELS:
        p = predict(design(rows, m), fit_logistic(design(fit_rows, m), y_fit))
        result[m] = {"ll": logloss_each(y, p), "p": p}
    return result, y, owner


def main():
    users = load()
    if len(users) < 30:
        sys.exit(f"Only {len(users)} usable users; run fetch.py first.")
    tune = [u for u in users if u["tune"]]

    # Chosen on the tuning half alone: fitted on one half of it, judged on the other half of it.
    half_a = lambda u: int(hashlib.sha256(u["handle"].encode()).hexdigest(), 16) % 4 == 0
    best = None
    for half_life in (None, 730, 365, 180):
        for tau in (25, 50, 100, 200):
            for lam in (0.0, 0.25, 0.5, 1.0):
                res, _, _ = score(tune, half_life, tau, lam, half_a, lambda u: not half_a(u))
                ll = float(np.mean(res["combined"]["ll"]))
                if best is None or ll < best[0]:
                    best = (ll, half_life, tau, lam)
    _, half_life, tau, lam = best

    res, y, owner = score(users, half_life, tau, lam,
                          lambda u: u["tune"], lambda u: not u["tune"])
    scored_users = np.unique(owner)
    print(f"{len(users)} users with enough rated rounds: {len(tune)} tuning, "
          f"{len(scored_users)} scored")
    print(f"{len(y)} scored contest problems, {int(y.sum())} solved in the round "
          f"({100 * y.mean():.1f}%)")
    print(f"chosen on the tuning half: half-life={half_life} days, tau={tau}, lambda={lam}\n")

    rng = np.random.default_rng(20261015)
    base = res["rating"]["ll"]
    print(f"{'model':16} {'log-loss':>9} {'Brier':>7} {'AUC':>6}   "
          f"{'vs rating (95% interval, x1000)':>32}")
    for m in MODELS:
        ll = res[m]["ll"]
        p = res[m]["p"]
        diff = ll - base
        boots = []
        for _ in range(2000):
            pick = rng.choice(scored_users, len(scored_users))
            mask = np.concatenate([np.flatnonzero(owner == i) for i in pick])
            boots.append(diff[mask].mean())
        lo, hi = np.percentile(boots, [2.5, 97.5])
        print(f"{m:16} {ll.mean():9.4f} {np.mean((p - y) ** 2):7.4f} {auc(y, p):6.3f}   "
              f"{1000 * diff.mean():+9.2f}  [{1000 * lo:+7.2f}, {1000 * hi:+7.2f}]")
    print("\nNegative is better than rating alone. An interval that crosses 0 is no evidence "
          "either way.")


if __name__ == "__main__":
    main()
