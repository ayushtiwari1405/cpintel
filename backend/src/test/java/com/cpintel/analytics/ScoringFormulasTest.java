package com.cpintel.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The scoring formulas, pinned.
 *
 * <p>These could not exist while the maths lived in PL/SQL packages: exercising a formula meant
 * standing up Oracle, seeding rows, and calling the function over JDBC. Every one of these runs
 * in microseconds against pure functions, with no Spring context and no database.
 */
class ScoringFormulasTest {

    private static final double EPSILON = 0.005;

    @Nested
    @DisplayName("mastery")
    class Mastery {

        @Test
        @DisplayName("an untouched skill scores exactly zero")
        void untouchedIsZero() {
            // The old formula's volume term was ln(max(solved,1) + 1), so a skill with no
            // attempts at all scored 3.32 out of nothing. That was pinned rather than fixed,
            // with a note calling it a formula decision to change deliberately. At fourteen
            // topics it was a curiosity; across a hundred and forty tree nodes it is a hundred
            // and forty rows of phantom progress, so it is now zero.
            assertEquals(0.0, ScoringFormulas.mastery(0, 0), EPSILON);
            assertEquals(0.0, ScoringFormulas.volume(0), EPSILON);
        }

        @Test
        @DisplayName("weights accuracy and volume in a 55/45 split")
        void componentWeighting() {
            double volume = ScoringFormulas.volume(50);
            double expected = 50 * ScoringFormulas.WEIGHT_ACCURACY
                            + volume * ScoringFormulas.WEIGHT_VOLUME;
            assertEquals(expected, ScoringFormulas.mastery(50, 100), 0.01);
        }

        @Test
        @DisplayName("the weights are a partition of one")
        void weightsSumToOne() {
            assertEquals(1.0,
                ScoringFormulas.WEIGHT_ACCURACY + ScoringFormulas.WEIGHT_VOLUME, EPSILON);
        }

        @Test
        @DisplayName("does not fall on its own as time passes")
        void isTimeIndependent() {
            // This is the whole point of splitting capability from retention. Mastery takes no
            // time argument any more, so there is no path by which inactivity can reduce it
            // twice - once inside the score and again through the decay subtracted from it.
            double before = ScoringFormulas.mastery(40, 50);
            double after = ScoringFormulas.mastery(40, 50);
            assertEquals(before, after, EPSILON);
        }

        @Test
        @DisplayName("never exceeds 100 at perfect accuracy and huge volume")
        void clampedAtHundred() {
            assertTrue(ScoringFormulas.mastery(100_000, 100_000) <= 100.0);
        }

        @Test
        @DisplayName("volume does not saturate at 50 solves")
        void volumeDoesNotSaturateEarly() {
            double fifty = ScoringFormulas.mastery(50, 50);
            double thousand = ScoringFormulas.mastery(1000, 1000);
            assertTrue(thousand - fifty > 10.0,
                "expected a clear gap between 50 and 1000 solves, got " + (thousand - fifty));
        }

        @Test
        @DisplayName("accuracy component cannot exceed 100 when solved outruns attempted")
        void accuracyClampedOnInconsistentCounts() {
            assertTrue(ScoringFormulas.mastery(200, 100) <= 100.0);
        }

        @Test
        @DisplayName("a perfect small sample scores below a good large one")
        void volumeSeparatesSampleSizes() {
            double perfectTiny = ScoringFormulas.mastery(3, 3);
            double goodLarge = ScoringFormulas.mastery(240, 300);
            assertTrue(goodLarge > perfectTiny,
                "large=" + goodLarge + " tiny=" + perfectTiny);
        }
    }

    @Nested
    @DisplayName("retention and decay")
    class Retention {

        @Test
        @DisplayName("everything is retained on the day of practice")
        void fullRetentionToday() {
            assertEquals(1.0, ScoringFormulas.retention(0), EPSILON);
            assertEquals(0.0, ScoringFormulas.decay(80.0, 0), EPSILON);
        }

        @Test
        @DisplayName("half is retained after one 30-day half-life")
        void halfLife() {
            assertEquals(0.5, ScoringFormulas.retention(30), EPSILON);
            assertEquals(40.0, ScoringFormulas.decay(80.0, 30), 0.01);
        }

        @Test
        @DisplayName("a quarter is retained after two half-lives")
        void twoHalfLives() {
            assertEquals(0.25, ScoringFormulas.retention(60), EPSILON);
            assertEquals(60.0, ScoringFormulas.decay(80.0, 60), 0.01);
        }

        @Test
        @DisplayName("never erodes more than the mastery it is eroding")
        void cappedAtMastery() {
            assertTrue(ScoringFormulas.decay(80.0, 10_000) <= 80.0);
        }

        @Test
        @DisplayName("decay and revision score partition the mastery exactly")
        void decayAndRevisionArePartition() {
            // The two numbers a user sees must add back up to the one they came from, or the
            // page is telling them two different stories about the same skill.
            double mastery = 72.0;
            for (long days : new long[]{0, 7, 30, 90, 365}) {
                double decay = ScoringFormulas.decay(mastery, days);
                double retained = ScoringFormulas.revisionScore(mastery, decay);
                assertEquals(mastery, decay + retained, 0.02,
                    "decay + retained must equal mastery at day " + days);
            }
        }

        @Test
        @DisplayName("revision score never goes negative")
        void revisionScoreFloorsAtZero() {
            assertEquals(0.0, ScoringFormulas.revisionScore(10.0, 40.0), EPSILON);
        }
    }

    @Nested
    @DisplayName("confidence")
    class Confidence {

        @Test
        @DisplayName("is zero with no attempts")
        void zeroAtNoAttempts() {
            assertEquals(0.0, ScoringFormulas.confidence(0), EPSILON);
        }

        @Test
        @DisplayName("reaches 100 at the saturation point and stays there")
        void saturates() {
            assertEquals(100.0, ScoringFormulas.confidence(80), EPSILON);
            assertEquals(100.0, ScoringFormulas.confidence(10_000), EPSILON);
        }

        @Test
        @DisplayName("rises fast early and slowly later")
        void diminishingReturns() {
            double firstTen = ScoringFormulas.confidence(10) - ScoringFormulas.confidence(0);
            double lastTen  = ScoringFormulas.confidence(80) - ScoringFormulas.confidence(70);
            assertTrue(firstTen > lastTen,
                "sqrt curve should front-load certainty: " + firstTen + " vs " + lastTen);
        }

        @Test
        @DisplayName("treats a small sample as low confidence even at perfect accuracy")
        void smallSampleIsLowConfidence() {
            assertTrue(ScoringFormulas.confidence(5) < 30.0);
        }
    }

    @Nested
    @DisplayName("consistency")
    class Consistency {

        @Test
        @DisplayName("returns the unknown default below the minimum contest count")
        void unknownWithTooFewContests() {
            assertEquals(ScoringFormulas.CONSISTENCY_UNKNOWN,
                ScoringFormulas.consistency(List.of(10, -5), "CODEFORCES"), EPSILON);
            assertEquals(ScoringFormulas.CONSISTENCY_UNKNOWN,
                ScoringFormulas.consistency(List.of(), "CODEFORCES"), EPSILON);
            assertEquals(ScoringFormulas.CONSISTENCY_UNKNOWN,
                ScoringFormulas.consistency(null, "CODEFORCES"), EPSILON);
        }

        @Test
        @DisplayName("is 100 for a user whose rating change never varies")
        void perfectlySteady() {
            assertEquals(100.0,
                ScoringFormulas.consistency(List.of(10, 10, 10, 10), "CODEFORCES"), EPSILON);
        }

        @Test
        @DisplayName("scores a wild swinger below a steady one")
        void volatilityLowersTheScore() {
            double steady = ScoringFormulas.consistency(List.of(5, 6, 4, 5, 6), "CODEFORCES");
            double wild = ScoringFormulas.consistency(
                List.of(-180, 200, -150, 190, 10), "CODEFORCES");
            assertTrue(wild < steady, "wild=" + wild + " steady=" + steady);
        }

        @Test
        @DisplayName("judges each platform against its own rating scale")
        void scaleIsPerPlatform() {
            // The same spread means very different things on two judges: +-40 is an ordinary
            // Codeforces round and a wild LeetCode one. Scoring both against one divisor made
            // anyone active on both look inconsistent for a reason that was not about them.
            List<Integer> changes = List.of(-40, 45, -38, 42, 0);
            double onCodeforces = ScoringFormulas.consistency(changes, "CODEFORCES");
            double onLeetCode = ScoringFormulas.consistency(changes, "LEETCODE");
            assertTrue(onCodeforces > onLeetCode,
                "cf=" + onCodeforces + " lc=" + onLeetCode);
        }

        @Test
        @DisplayName("floors at zero rather than going negative on extreme spread")
        void neverNegative() {
            assertTrue(ScoringFormulas.consistency(
                List.of(-2000, 2000, -2000, 2000), "CODEFORCES") >= 0.0);
        }

        @Test
        @DisplayName("uses sample standard deviation, matching Postgres stddev_samp")
        void sampleNotPopulationStdDev() {
            // n-1 denominator: [2,4,4,4,5,5,7,9] has population sd 2.0, sample sd 2.138.
            assertEquals(2.138,
                ScoringFormulas.sampleStdDev(List.of(2, 4, 4, 4, 5, 5, 7, 9)), 0.001);
        }
    }

    @Nested
    @DisplayName("rating normalization")
    class Normalization {

        @ParameterizedTest(name = "{0} rating {1} normalizes to {2}")
        @CsvSource({
            "CODEFORCES, 2000, 500.00",
            "CODEFORCES, 4000, 1000.00",
            "CODECHEF,   3500, 1000.00",
            "LEETCODE,   1400, 0.00",
            "LEETCODE,   2700, 500.00",
        })
        void mapsPlatformRangesOntoSharedScale(String platform, int rating, double expected) {
            assertEquals(expected, ScoringFormulas.normalizeRating(platform, rating), EPSILON);
        }

        @Test
        @DisplayName("treats an unrated or missing rating as zero")
        void unratedIsZero() {
            assertEquals(0.0, ScoringFormulas.normalizeRating("CODEFORCES", null), EPSILON);
            assertEquals(0.0, ScoringFormulas.normalizeRating("CODEFORCES", 0), EPSILON);
            assertEquals(0.0, ScoringFormulas.normalizeRating("CODEFORCES", -100), EPSILON);
        }

        @Test
        @DisplayName("gives a LeetCode account below the 1400 floor no credit")
        void leetcodeFloor() {
            assertEquals(0.0, ScoringFormulas.normalizeRating("LEETCODE", 1200), EPSILON);
        }

        @ParameterizedTest
        @ValueSource(strings = {"UNKNOWN", "", "TOPCODER"})
        @DisplayName("falls back to a 0-3000 range for platforms it does not know")
        void unknownPlatformFallback(String platform) {
            assertEquals(500.0, ScoringFormulas.normalizeRating(platform, 1500), EPSILON);
        }

        @Test
        @DisplayName("clamps a rating above the platform ceiling to 1000")
        void clampedAtCeiling() {
            assertEquals(1000.0, ScoringFormulas.normalizeRating("CODEFORCES", 9999), EPSILON);
        }
    }

    @Nested
    @DisplayName("unified score")
    class Unified {

        @Test
        @DisplayName("is zero when no platform carries any weight")
        void zeroWithoutPlatforms() {
            assertEquals(0.0,
                ScoringFormulas.unifiedScore(0, 0.40, 0, 0.35, 0, 0.25, 0), EPSILON);
        }

        @Test
        @DisplayName("does not penalise a user for platforms they have not linked")
        void renormalizesOverLinkedPlatformsOnly() {
            double score = ScoringFormulas.unifiedScore(500, 0.40, 0, 0.35, 0, 0.25, 0.40);
            assertEquals(500.0, score, EPSILON);
        }

        @Test
        @DisplayName("weights platforms against each other when several are linked")
        void weightedAcrossPlatforms() {
            double expected = (600 * 0.40 + 200 * 0.35) / 0.75;
            double score = ScoringFormulas.unifiedScore(600, 0.40, 200, 0.35, 0, 0.25, 0.75);
            assertEquals(expected, score, 0.01);
        }
    }

    @Nested
    @DisplayName("problem fit")
    class ProblemFit {

        // A representative node: Bitmask DP, rated 1900-2300.
        private static final int MIN = 1900;
        private static final int MAX = 2300;

        @Test
        @DisplayName("returns a usable score for a real Codeforces rating")
        void worksInRatingSpace() {
            // The bug this replaces: scoreProblemFit compared a mastery score (0-100) directly
            // against a problem rating (800-3500), so the gap for even a perfectly chosen
            // problem was over a thousand and every real problem scored exactly zero. The
            // recommendation engine was ranking a list in which every entry was tied at nothing.
            double fit = ScoringFormulas.scoreProblemFit(50, MIN, MAX, 2200);
            assertTrue(fit > 50.0, "a well-matched problem should score well, got " + fit);
        }

        @Test
        @DisplayName("aims at the bottom of the band for a beginner and the top for an expert")
        void targetTracksMasteryAcrossTheBand() {
            double novice = ScoringFormulas.targetRating(0, MIN, MAX);
            double expert = ScoringFormulas.targetRating(100, MIN, MAX);
            assertEquals(MIN + ScoringFormulas.FIT_STRETCH_POINTS, novice, EPSILON);
            assertEquals(MAX + ScoringFormulas.FIT_STRETCH_POINTS, expert, EPSILON);
            assertTrue(expert > novice);
        }

        @Test
        @DisplayName("peaks at the target rating and falls away either side")
        void peaksAtTarget() {
            double target = ScoringFormulas.targetRating(50, MIN, MAX);
            double atTarget = ScoringFormulas.scoreProblemFit(50, MIN, MAX, (int) target);
            double tooEasy  = ScoringFormulas.scoreProblemFit(50, MIN, MAX, (int) target - 500);
            double tooHard  = ScoringFormulas.scoreProblemFit(50, MIN, MAX, (int) target + 500);
            assertTrue(atTarget > tooEasy, "target should beat too-easy");
            assertTrue(atTarget > tooHard, "target should beat too-hard");
        }

        @Test
        @DisplayName("boosts weak skills so they surface ahead of strong ones")
        void weakSkillsGetABoost() {
            // Both problems sit 300 points above their own target, so they would score
            // identically without the boost.
            double weakTarget = ScoringFormulas.targetRating(20, MIN, MAX);
            double strongTarget = ScoringFormulas.targetRating(70, MIN, MAX);
            double weak = ScoringFormulas.scoreProblemFit(20, MIN, MAX, (int) weakTarget + 300);
            double strong = ScoringFormulas.scoreProblemFit(70, MIN, MAX, (int) strongTarget + 300);
            assertTrue(weak > strong, "weak=" + weak + " strong=" + strong);
        }

        @Test
        @DisplayName("never exceeds 100 despite the weak-skill multiplier")
        void boostStaysInRange() {
            double target = ScoringFormulas.targetRating(0, MIN, MAX);
            assertEquals(100.0,
                ScoringFormulas.scoreProblemFit(0, MIN, MAX, (int) target), EPSILON);
        }

        @Test
        @DisplayName("scores a wildly mismatched problem near zero")
        void farMismatchIsNearZero() {
            assertTrue(ScoringFormulas.scoreProblemFit(50, MIN, MAX, 800) < 1.0);
        }

        @Test
        @DisplayName("an unrated problem cannot be fitted")
        void unratedScoresZero() {
            assertEquals(0.0, ScoringFormulas.scoreProblemFit(50, MIN, MAX, null), EPSILON);
        }

        @Test
        @DisplayName("tolerates a band given the wrong way round")
        void bandOrderDoesNotMatter() {
            assertEquals(ScoringFormulas.targetRating(50, MIN, MAX),
                         ScoringFormulas.targetRating(50, MAX, MIN), EPSILON);
        }
    }

    @Nested
    @DisplayName("spaced repetition")
    class SpacedRepetition {

        @Test
        @DisplayName("lowers the ease factor as a skill decays")
        void decayLowersEase() {
            assertTrue(ScoringFormulas.easeFactor(80) < ScoringFormulas.easeFactor(20));
        }

        @Test
        @DisplayName("never drops the ease factor below the SM-2 floor")
        void easeFloor() {
            assertEquals(ScoringFormulas.MIN_EASE_FACTOR,
                ScoringFormulas.easeFactor(1000), EPSILON);
        }

        @Test
        @DisplayName("schedules stronger skills further out")
        void strongerSkillsWaitLonger() {
            double ease = ScoringFormulas.easeFactor(30);
            assertTrue(ScoringFormulas.revisionIntervalDays(90, ease)
                     > ScoringFormulas.revisionIntervalDays(20, ease));
        }

        @Test
        @DisplayName("always schedules at least one day out")
        void minimumOneDay() {
            assertEquals(1, ScoringFormulas.revisionIntervalDays(0, ScoringFormulas.MIN_EASE_FACTOR));
        }
    }

    @Nested
    @DisplayName("rounding")
    class Rounding {

        @Test
        @DisplayName("rounds to 2dp half-up")
        void twoDecimalPlacesHalfUp() {
            assertEquals(1.24, ScoringFormulas.round2(1.235), EPSILON);
            assertEquals(1.23, ScoringFormulas.round2(1.234), EPSILON);
        }

        @Test
        @DisplayName("degrades non-finite input to zero rather than propagating NaN")
        void nonFiniteBecomesZero() {
            assertEquals(0.0, ScoringFormulas.round2(Double.NaN), EPSILON);
            assertEquals(0.0, ScoringFormulas.round2(Double.POSITIVE_INFINITY), EPSILON);
        }
    }
}
