package com.cpintel.analytics;

import com.cpintel.analytics.SkillRating.Evidence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillRatingTest {

    private static List<Evidence> rounds(int count, int difficulty, int rating, boolean solved) {
        List<Evidence> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(new Evidence(difficulty, rating, solved, 0));
        return out;
    }

    @Test
    @DisplayName("on the Elo curve, a problem at your rating is a coin flip")
    void curve() {
        assertEquals(0.5, SkillRating.solveProbability(1600, 1600), 1e-9);
        assertEquals(1 / 1.1, SkillRating.solveProbability(2000, 1600), 1e-9);
    }

    @Test
    @DisplayName("with no evidence a topic sits at the person's rating")
    void noEvidence() {
        SkillRating.Estimate e = SkillRating.estimate(List.of());
        assertEquals(0.0, e.offset());
        assertEquals(SkillRating.PRIOR_SD, e.sd());
    }

    @Test
    @DisplayName("solving what you were expected to solve half the time moves you up, failing moves you down")
    void direction() {
        assertTrue(SkillRating.estimate(rounds(10, 1600, 1600, true)).offset() > 0);
        assertTrue(SkillRating.estimate(rounds(10, 1600, 1600, false)).offset() < 0);
    }

    @Test
    @DisplayName("results exactly as the rating predicts leave the offset at zero")
    void calibrated() {
        List<Evidence> evidence = new ArrayList<>(rounds(20, 1600, 1600, true));
        evidence.addAll(rounds(20, 1600, 1600, false));
        assertEquals(0.0, SkillRating.estimate(evidence).offset(), 0.5);
    }

    /** The case the old mastery score got most wrong: one solve made a topic look strong. */
    @Test
    @DisplayName("one lucky solve moves a topic only a little, and says it is unsure")
    void oneSolveIsWeakEvidence() {
        SkillRating.Estimate one = SkillRating.estimate(rounds(1, 2000, 1500, true));
        SkillRating.Estimate many = SkillRating.estimate(rounds(30, 2000, 1500, true));

        // A 1500 solving a 2000 in a round is a 5% event, so it does move the topic, but by a
        // fraction of the 500-point gap, and the estimate stays nearly as unsure as the prior.
        assertTrue(one.offset() > 0 && one.offset() < SkillRating.PRIOR_SD,
            "one solve: " + one.offset());
        assertTrue(one.sd() > 0.9 * SkillRating.PRIOR_SD);
        assertTrue(many.offset() > one.offset());
        assertTrue(many.sd() < one.sd());
    }

    @Test
    @DisplayName("difficulty counts: solving hard problems says more than solving easy ones")
    void difficultyMatters() {
        double easy = SkillRating.estimate(rounds(10, 1000, 1500, true)).offset();
        double hard = SkillRating.estimate(rounds(10, 1900, 1500, true)).offset();
        assertTrue(hard > easy, "hard " + hard + " vs easy " + easy);
    }

    @Test
    @DisplayName("an old round counts for less than a recent one")
    void olderCountsLess() {
        double recent = SkillRating.estimate(List.of(new Evidence(1800, 1500, true, 0))).offset();
        double old = SkillRating.estimate(
            List.of(new Evidence(1800, 1500, true, 3 * SkillRating.HALF_LIFE_DAYS))).offset();
        assertTrue(old < recent);
        assertTrue(old > 0);
    }

    @Test
    @DisplayName("with no evidence a node sits at its topic's offset; its own evidence moves it from there")
    void nodeLeansOnItsTopic() {
        SkillRating.Estimate none = SkillRating.estimate(List.of(), 60, SkillRating.NODE_SD);
        assertEquals(60, none.offset());
        assertEquals(SkillRating.NODE_SD, none.sd());

        double failing = SkillRating.estimate(rounds(10, 1600, 1600, false), 60,
            SkillRating.NODE_SD).offset();
        assertTrue(failing < 60, "own failures pull it below the topic: " + failing);
        double fromZero = SkillRating.estimate(rounds(10, 1600, 1600, false)).offset();
        assertTrue(failing > fromZero, "but not as far as it would fall from zero");
    }
}
