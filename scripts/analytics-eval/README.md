# Skill-model evaluation

Do CPIntel's skill numbers predict anything? These two scripts answer that on real Codeforces
histories, and they are why the analytics show topic ratings rather than the old 0-100 mastery
score.

```bash
./scripts/analytics-eval/fetch.py --users 300   # ~1 hour: Codeforces asks for one call per 2 s
./scripts/analytics-eval/evaluate.py            # needs numpy
```

Downloads land in `data/` (gitignored). `fetch.py` resumes where it stopped.

## What is predicted

Which problems of a person's **later rated rounds** they solve during the round. Contests rather
than practice, because in a round you meet every problem: an unsolved one is a real failure. A
practice history holds only the problems somebody chose to submit to, and about 94% of those end
accepted — people submit once they think they have it — so "solved out of attempted" in practice
is close to a constant for everyone.

Each person's rounds are split in time (earlier 75% are history, the rest predicted), and people
are split in two by a hash of their handle: every parameter and calibration is fitted on one half
and scored on the other.

Ratings are corrected for accounts made since 2020, which go into their first round shown at 0
while Codeforces rates them at 1400 underneath and catch up over six rounds.

## Result (15 Oct 2026, 300 sampled users, 249 with enough rounds)

129 scored users, 14,259 contest problems. Log-loss: lower is better; the difference is against
the rating alone, x1000, with a 95% bootstrap interval over users.

| Model | Log-loss | vs rating alone |
|---|---|---|
| Rating alone | 0.2144 | — |
| Old mastery score + rating | 0.2143 | −0.13 [−0.73, +0.43] |
| **Topic offsets from rated rounds** | **0.2130** | **−1.39 [−2.82, +0.04]** |
| Topic offsets from practice | 0.2827 | +68 (worse) |

Chosen on the tuning half: prior spread 100 rating points, half-life 180 days, and no weight on
practice offsets (adding them never helped).

What this means:

- **The old mastery score added nothing** to the rating. Its accuracy half was nearly constant
  (see above) and the rest was volume.
- **Topic offsets from rounds help, a little** — about 0.65%, with an interval that only just
  reaches zero. The rating explains most of what anybody solves.
- **Offsets are small.** Their spread is about 30 points, and only about one in twenty is larger
  than its own uncertainty. So the analytics call a topic a strength or a weak spot only when it
  clears its ±, and otherwise say it is in line with the rating.
