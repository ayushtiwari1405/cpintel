package com.cpintel.analytics;

import java.util.Collection;

/**
 * A skill rating per topic, in Codeforces rating points: how this person does on a topic's
 * problems compared with what their overall rating predicts.
 *
 * <h2>The model</h2>
 *
 * <p>A Codeforces problem rating is defined on the Elo curve: a problem rated {@code d} is one a
 * contestant rated {@code d} solves in the round half the time. So a contestant going into a round
 * at rating {@code R} solves a problem of rating {@code d} with probability
 * {@code 1 / (1 + 10^((d - (R + offset)) / 400))}, where {@code offset} is how much better or worse
 * than their rating they do on that problem's topic. The topic's rating is the current rating plus
 * that offset.
 *
 * <p>The offset is estimated from rated rounds only: every problem of every round the person sat,
 * solved during the round or not. Not from practice. A practice history holds only the problems
 * somebody chose to submit to, and over 90% of those end accepted for nearly everyone, so practice
 * says how much someone has done but almost nothing about where they fail. Measured on real
 * histories, offsets taken from practice made predictions worse than the rating alone, and offsets
 * taken from rounds made them better (scripts/analytics-eval).
 *
 * <p>Each offset has a normal prior centred on zero with spread {@link #PRIOR_SD}: with no
 * evidence a topic is assumed to sit at the person's rating, and it moves away only as far as the
 * evidence carries it. That is what keeps one lucky round from making anybody a DP expert. Older
 * rounds count for less, halving every {@link #HALF_LIFE_DAYS} days.
 *
 * <h2>What it was measured to do</h2>
 *
 * <p>On 249 real histories (scripts/analytics-eval, 15 Oct 2026), predicting which problems of
 * their later rated rounds people solved, scored on users the parameters were not tuned on:
 * the old mastery score added nothing to the rating alone (log-loss -0.13 x 10^-3, interval
 * across zero); these offsets improved it by 1.4 x 10^-3, about 0.65%, with an interval that
 * only just reaches zero. The rating explains most of it. Offsets are small — their spread is
 * about 30 points, and only around one in twenty is larger than its own uncertainty — so the
 * page calls a topic a strength or a weak spot only when its offset clears its {@code sd}.
 * {@link #PRIOR_SD} and {@link #HALF_LIFE_DAYS} are the values chosen on the tuning half.
 *
 * <p>Pure functions over their inputs, like {@link ScoringFormulas}.
 */
public final class SkillRating {

    private SkillRating() {}

    /** Spread of the prior on an offset, in rating points. Chosen on held-out users. */
    public static final double PRIOR_SD = 100.0;

    /** Evidence halves in weight over this many days. Chosen on held-out users. */
    public static final double HALF_LIFE_DAYS = 180.0;

    /** Natural log of ten over 400: the Elo scale. */
    static final double K = Math.log(10) / 400;

    /**
     * One problem of one rated round.
     *
     * @param difficulty   the problem's rating
     * @param ratingBefore the person's rating going into the round
     * @param solved       accepted during the round
     * @param ageDays      days between the round and now
     */
    public record Evidence(int difficulty, int ratingBefore, boolean solved, double ageDays) {}

    /**
     * @param offset rating points above (or below) the person's rating, on this topic
     * @param sd     how uncertain the offset is, one standard deviation, in rating points
     * @param rounds how many problems the estimate rests on
     */
    public record Estimate(double offset, double sd, int problems) {
        public static final Estimate NONE = new Estimate(0.0, PRIOR_SD, 0);
    }

    /** The most likely offset given this evidence, and how sure of it to be. */
    public static Estimate estimate(Collection<Evidence> evidence) {
        if (evidence == null || evidence.isEmpty()) return Estimate.NONE;

        double offset = 0.0;
        double curvature = 1 / (PRIOR_SD * PRIOR_SD);
        for (int iteration = 0; iteration < 30; iteration++) {
            double gradient = -offset / (PRIOR_SD * PRIOR_SD);
            curvature = 1 / (PRIOR_SD * PRIOR_SD);
            for (Evidence e : evidence) {
                double w = weight(e.ageDays());
                double p = solveProbability(e.ratingBefore() + offset, e.difficulty());
                gradient += w * K * ((e.solved() ? 1 : 0) - p);
                curvature += w * K * K * p * (1 - p);
            }
            double step = gradient / curvature;
            offset += step;
            if (Math.abs(step) < 0.01) break;
        }
        return new Estimate(offset, 1 / Math.sqrt(curvature), evidence.size());
    }

    /** Probability of solving a problem of {@code difficulty} in a round at skill {@code skill}. */
    public static double solveProbability(double skill, double difficulty) {
        return 1 / (1 + Math.pow(10, (difficulty - skill) / 400));
    }

    static double weight(double ageDays) {
        return ageDays <= 0 ? 1.0 : Math.pow(0.5, ageDays / HALF_LIFE_DAYS);
    }
}
