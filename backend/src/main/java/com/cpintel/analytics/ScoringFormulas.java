package com.cpintel.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Locale;

/**
 * Every scoring formula CPIntel uses, as pure functions.
 *
 * <p>These were PL/SQL package functions (pkg_analytics, pkg_unified_rating,
 * pkg_recommendation). Four of the twenty Oracle migrations existed only to patch numbers in
 * this file's worth of arithmetic, because there was no way to change a formula without
 * shipping a migration and no way to test one without a database. Keeping the maths pure and
 * free of I/O is the point of the move: everything here is directly unit-testable, and tuning a
 * weight is now a code change.
 *
 * <h2>Two numbers, not one</h2>
 *
 * <p>Mastery used to fold recency into itself <em>and</em> then have decay subtracted from it,
 * so a topic left alone was penalised twice for the same inactivity: once by the recency term
 * inside the mastery score, and again by the decay term taken off it. At ninety days a topic
 * had already lost its whole 25-point recency component before decay removed most of what
 * remained.
 *
 * <p>The two ideas are now separate and each means one thing:
 *
 * <ul>
 *   <li>{@link #mastery(int, int)} — <b>capability</b>. How good the user got at this, from
 *       accuracy and volume. Time does not enter into it, so it never falls on its own.
 *   <li>{@link #retention(long)} — <b>what survives</b>. A 0-1 multiplier that halves every
 *       thirty idle days.
 * </ul>
 *
 * <p>{@link #decay(double, long)} is the difference between them — the part that has faded —
 * and {@link #revisionScore(double, double)} is what is left. Multiplying once is the whole
 * fix; nothing is subtracted twice.
 *
 * <p>All outputs are rounded to 2dp with HALF_UP.
 */
public final class ScoringFormulas {

    private ScoringFormulas() {}

    // ── Mastery ────────────────────────────────────────────────────────────

    /**
     * Relative weights of the two mastery components. They sum to 1.0.
     *
     * <p>There were three, and recency was the third at 0.25. It has been removed rather than
     * reweighted: see the class comment. The remaining two keep roughly the ratio they had.
     */
    public static final double WEIGHT_ACCURACY = 0.55;
    public static final double WEIGHT_VOLUME   = 0.45;

    /**
     * Volume saturates near 100 only after roughly 1500 solves. The original base was 52, which
     * let a high-fanout topic like Arrays max out at the same level as a niche topic with 50
     * solves.
     */
    public static final double VOLUME_LOG_BASE = 1500.0;

    /** Recency in days used when a topic has never been practised. */
    public static final int RECENCY_NEVER_DAYS = 999;

    /**
     * How much a user has demonstrably learned in one skill, 0-100: how often they are right,
     * and how much they have done. Deliberately time-independent — see the class comment.
     *
     * @param solved    distinct problems solved, not accepted submissions
     * @param attempted distinct problems attempted, not total submissions
     */
    public static double mastery(int solved, int attempted) {
        double accuracy = attempted > 0
            ? Math.min(100.0, (double) solved / attempted * 100.0)
            : 0.0;

        double mastery = accuracy * WEIGHT_ACCURACY + volume(solved) * WEIGHT_VOLUME;
        return round2(clamp(mastery, 0, 100));
    }

    /**
     * The volume component alone, 0-100.
     *
     * <p>Uses {@code log1p} so that zero solves scores zero. The previous form was
     * {@code ln(max(solved,1) + 1)}, which gave an untouched topic {@code ln(2)} and therefore
     * a mastery of 3.32 out of nothing at all. That floor was inherited from the PL/SQL and
     * pinned by a test that called it a formula decision to fix deliberately; this is that fix.
     * Every untouched skill in a 140-node tree scoring 3.32 is 140 rows of noise.
     */
    public static double volume(int solved) {
        if (solved <= 0) return 0.0;
        double v = Math.log1p(solved) / Math.log1p(VOLUME_LOG_BASE) * 100.0;
        return round2(clamp(v, 0, 100));
    }

    // ── Retention and decay ────────────────────────────────────────────────

    /** Retention is treated as halving every 30 days of inactivity. */
    public static final double DECAY_HALF_LIFE_DAYS = 30.0;

    /**
     * The fraction of a skill still available after this long without practice, 0-1.
     * 1.0 on the day it was practised, 0.5 after thirty days, and so on.
     */
    public static double retention(long daysSincePractice) {
        if (daysSincePractice <= 0) return 1.0;
        return Math.pow(0.5, daysSincePractice / DECAY_HALF_LIFE_DAYS);
    }

    /**
     * How much of {@code masteryScore} has faded through inactivity. Never exceeds the mastery
     * it is eroding.
     */
    public static double decay(double masteryScore, long daysSincePractice) {
        if (daysSincePractice <= 0) return 0.0;
        double decayed = masteryScore * (1 - retention(daysSincePractice));
        return round2(clamp(decayed, 0, masteryScore));
    }

    /** What is left of a skill once decay is taken off it — the number to revise against. */
    public static double revisionScore(double masteryScore, double decayScore) {
        return round2(Math.max(0.0, masteryScore - decayScore));
    }

    // ── Confidence ─────────────────────────────────────────────────────────

    /** Attempts at which confidence in a mastery number reaches 100. */
    public static final int CONFIDENCE_SATURATION_ATTEMPTS = 80;

    /**
     * How much the mastery number can be trusted, based on sample size alone. A skill with 5
     * attempts is low confidence even at 100% accuracy; 80+ attempts is full confidence
     * regardless of the score. Square-root curve, so early attempts move confidence quickly and
     * later ones add diminishing certainty.
     */
    public static double confidence(int attempted) {
        double confidence = Math.min(100.0,
            Math.sqrt((double) Math.max(attempted, 0) / CONFIDENCE_SATURATION_ATTEMPTS) * 100.0);
        return round2(confidence);
    }

    // ── Contest-derived scores ─────────────────────────────────────────────

    /** Share of contests where the user gained rating, 0-100. */
    public static double accuracy(int totalContests, int ratingGains) {
        if (totalContests == 0) return 0.0;
        return round2((double) ratingGains / totalContests * 100.0);
    }

    /** Fewer than this many contests and consistency is not meaningful. */
    public static final int CONSISTENCY_MIN_CONTESTS = 3;

    /** Consistency defaults to this until there are enough contests to judge. */
    public static final double CONSISTENCY_UNKNOWN = 50.0;

    /**
     * Rating-change spread at which consistency reaches zero, per platform.
     *
     * <p>Consistency was a single {@code 100 - stddev/2} over every contest the user had ever
     * sat, on any judge. That mixes scales: Codeforces deltas run to a few hundred points and
     * LeetCode's to a few dozen, so a user active on both was scored as wildly inconsistent for
     * the ordinary reason that their two judges do not use the same units. Each platform now
     * gets its own divisor and the results are combined afterwards.
     */
    public static double ratingChangeScale(String platform) {
        if (platform == null) return 150.0;
        return switch (platform.toUpperCase(Locale.ROOT)) {
            case "CODEFORCES" -> 150.0;
            case "LEETCODE"   -> 80.0;
            case "CODECHEF"   -> 120.0;
            default           -> 150.0;
        };
    }

    /**
     * Consistency, 0-100, for one platform's rating changes: the spread of results, inverted
     * against that platform's own scale. A user whose results swing wildly scores low. Returns
     * {@link #CONSISTENCY_UNKNOWN} below {@link #CONSISTENCY_MIN_CONTESTS} contests rather than
     * reading noise as signal.
     */
    public static double consistency(Collection<Integer> ratingChanges, String platform) {
        if (ratingChanges == null || ratingChanges.size() < CONSISTENCY_MIN_CONTESTS) {
            return CONSISTENCY_UNKNOWN;
        }
        double scale = Math.max(ratingChangeScale(platform), 1.0);
        double spread = sampleStdDev(ratingChanges);
        return round2(clamp(100.0 * (1.0 - spread / scale), 0, 100));
    }

    /** Sample standard deviation (n-1), matching Postgres stddev_samp. */
    public static double sampleStdDev(Collection<Integer> values) {
        int n = values.size();
        if (n < 2) return 0.0;
        double mean = values.stream().mapToInt(Integer::intValue).average().orElse(0);
        double sumSq = values.stream()
            .mapToDouble(v -> (v - mean) * (v - mean))
            .sum();
        return Math.sqrt(sumSq / (n - 1));
    }

    // ── Unified rating ─────────────────────────────────────────────────────

    /**
     * Rating range used to map each platform onto the shared 0-1000 scale. LeetCode starts at
     * 1400 because that is roughly where its contest rating floor sits, so a new LeetCode
     * account is not treated as equivalent to an unrated Codeforces one.
     */
    public static double normalizeRating(String platform, Integer rating) {
        if (rating == null || rating <= 0) return 0.0;

        int min, max;
        switch (platform == null ? "" : platform) {
            case "CODEFORCES" -> { min = 0;    max = 4000; }
            case "LEETCODE"   -> { min = 1400; max = 4000; }
            case "CODECHEF"   -> { min = 0;    max = 3500; }
            default           -> { min = 0;    max = 3000; }
        }

        double normalized = ((double) (rating - min) / Math.max(max - min, 1)) * 1000.0;
        return round2(clamp(normalized, 0, 1000));
    }

    /**
     * Weighted mean of the normalized per-platform scores, re-normalized by the weights of the
     * platforms actually linked. A user with only Codeforces linked is scored on Codeforces
     * alone rather than being penalised for the two missing platforms.
     *
     * @return 0 when no platform contributes any weight.
     */
    public static double unifiedScore(double cfNorm, double cfWeight,
                                      double lcNorm, double lcWeight,
                                      double ccNorm, double ccWeight,
                                      double totalWeight) {
        if (totalWeight <= 0) return 0.0;
        double weighted = (cfNorm * cfWeight) + (lcNorm * lcWeight) + (ccNorm * ccWeight);
        return round2(weighted / totalWeight);
    }

    // ── Recommendation fit ─────────────────────────────────────────────────

    /**
     * How far above the user's current level inside a band to aim, in rating points. Small
     * enough to stay solvable, big enough that it is not revision.
     */
    public static final int FIT_STRETCH_POINTS = 100;

    /**
     * Spread of the fit curve in rating points. At one sigma off target a problem keeps about
     * 60% of its score, at two sigma about 14%.
     */
    public static final double FIT_SIGMA = 250.0;

    /** Below this mastery a skill is "weak" and its problems get a fit boost. */
    public static final int FIT_WEAK_TOPIC_THRESHOLD = 40;

    /** Multiplier applied to a weak skill's problems so they surface ahead of strong ones. */
    public static final double FIT_WEAK_BOOST = 1.2;

    /**
     * The rating to aim at for a user at {@code masteryScore} within a node's band.
     *
     * <p>This is the conversion that was missing. {@code scoreProblemFit} used to compare a
     * mastery score (0-100) directly against a Codeforces rating (800-3500), so the gap for a
     * perfectly-chosen 1500-rated problem came out around 1430 and every real problem scored
     * zero. Mastery and difficulty are different units and one has to be mapped into the other
     * before they can be subtracted.
     *
     * <p>A beginner in a node is aimed at the bottom of its band, someone who has mastered it at
     * the top, plus a small stretch either way.
     */
    public static double targetRating(double masteryScore, int minRating, int maxRating) {
        int lo = Math.min(minRating, maxRating);
        int hi = Math.max(minRating, maxRating);
        double position = clamp(masteryScore, 0, 100) / 100.0;
        return round2(lo + (hi - lo) * position + FIT_STRETCH_POINTS);
    }

    /**
     * How well a problem of {@code problemRating} suits a user at {@code masteryScore} in a node
     * whose band is {@code [minRating, maxRating]}, 0-100.
     *
     * <p>Peaks at {@link #targetRating} and falls away as a Gaussian either side, so a problem
     * 200 points off is merely a worse choice rather than an invalid one. Weak skills get a
     * boost so they surface ahead of already-strong ones.
     */
    public static double scoreProblemFit(double masteryScore, int minRating, int maxRating,
                                         Integer problemRating) {
        if (problemRating == null) return 0.0;
        double target = targetRating(masteryScore, minRating, maxRating);
        double gap = problemRating - target;
        double score = 100.0 * Math.exp(-(gap * gap) / (2 * FIT_SIGMA * FIT_SIGMA));
        if (masteryScore < FIT_WEAK_TOPIC_THRESHOLD) score *= FIT_WEAK_BOOST;
        return round2(clamp(score, 0, 100));
    }

    // ── Spaced repetition (SM-2 derived) ───────────────────────────────────

    /** Floor on the SM-2 ease factor; below this, intervals stop growing usefully. */
    public static final double MIN_EASE_FACTOR = 1.3;

    /** Ease starts here and is pulled down in proportion to how much a skill has decayed. */
    public static final double BASE_EASE_FACTOR = 2.5;

    /** A heavily decayed skill gets a lower ease factor, so it comes back around sooner. */
    public static double easeFactor(double decayScore) {
        return Math.max(MIN_EASE_FACTOR, BASE_EASE_FACTOR - (decayScore / 100.0));
    }

    /** Days until the next revision of a skill. Stronger skills wait longer. */
    public static int revisionIntervalDays(double masteryScore, double ease) {
        return (int) Math.max(1, Math.round(masteryScore / 20.0 * ease));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    /** Half away from zero, to 2dp. */
    static double round2(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
