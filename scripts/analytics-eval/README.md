# Skill-model evaluation

Do CPIntel's skill numbers predict anything? These two scripts answer that on real Codeforces
histories, and they are why the analytics show topic ratings rather than the old 0-100 mastery
score.

```bash
./scripts/analytics-eval/fetch.py --users 300   # ~1 hour: Codeforces asks for one call per 2 s
./scripts/analytics-eval/fetch.py --users 600   # grows the sample; resumes where it stopped
(cd backend && ./mvnw -q compile)               # evaluate.py maps problems to nodes with it
./scripts/analytics-eval/evaluate.py            # needs numpy and Java; ~10 minutes
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

## Result (15 Oct 2026, 600 sampled users, 488 with enough rounds)

250 scored users, 26,255 contest problems. Log-loss: lower is better; differences x1000, with a
95% bootstrap interval over users. Nodes and topics come from the backend's own attribution
(`AttributionExport.java`), so the node rows score exactly what the product computes.

| Model | Log-loss | vs rating alone | vs per-node |
|---|---|---|---|
| Rating alone | 0.2143 | — | +1.43 |
| Old mastery score + rating | 0.2142 | −0.09 [−0.42, +0.26] | |
| Per Codeforces tag | 0.2131 | −1.20 [−2.09, −0.32] | +0.22 [−0.51, +0.95] |
| Per roll-up topic | 0.2127 | −1.59 [−2.40, −0.74] | −0.16 [−0.58, +0.29] |
| Per node, drawn to zero | 0.2129 | −1.43 [−1.97, −0.84] | — |
| **Per node, drawn to its topic** | **0.2126** | **−1.68 [−2.42, −0.89]** | **−0.25 [−0.48, −0.02]** |
| Per node, tries count as half a solve | 0.2130 | −1.35 [−2.06, −0.58] | +0.08 [−0.33, +0.47] |

Chosen on the tuning half: half-life 180 days; topics drawn to zero with spread 100 (shown) and
50 (as the centre a node is drawn to); nodes drawn to their topic with spread 100.

What this means:

- **The old mastery score added nothing** to the rating. Its accuracy half was nearly constant
  (see above) and the rest was volume.
- **Skill offsets from rounds help, a little, and now clearly** — about 0.8% for the model the
  product uses, an interval well clear of zero on the larger sample. The rating explains most of
  what anybody solves.
- **A node leaning on its topic beats a node on its own**, by a small margin also clear of zero:
  a node has little evidence, and its topic is the best guess where it has none.
- **Counting an unsolved try as partial credit adds nothing**, so it is not used.
- **Offsets are small.** Their spread is about 30 points, and only about one in twenty is larger
  than its own uncertainty. So the analytics call a topic a strength or a weak spot only when it
  clears its ±, and otherwise say it is in line with the rating.

An earlier run on 300 users (249 usable) found the per-tag gain at −1.39 [−2.82, +0.04]: the
same direction, an interval that the doubled sample has since narrowed off zero.
