package com.cpintel.service;

import com.cpintel.entity.TopicMastery;
import com.cpintel.roadmap.RoadmapTaxonomy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** When a node opens and when it is done, with a skill rating and without one. */
class RoadmapRulesTest {

    /** DP Basics: band 1200-1500. */
    private static final RoadmapTaxonomy.NodeDef DP = RoadmapTaxonomy.byId("dp-basics");

    private static TopicMastery rated(double rating, int solved) {
        return TopicMastery.builder().skillRating(rating).problemsSolved(solved)
            .problemsAttempted(solved).build();
    }

    private static TopicMastery coverage(int solved, Integer hardest) {
        return TopicMastery.builder().problemsSolved(solved).problemsAttempted(solved)
            .maxSolvedRating(hardest).build();
    }

    @Test
    @DisplayName("rated: a prerequisite clears a third of the way into its band")
    void ratedUnlock() {
        assertFalse(RoadmapService.cleared(DP, rated(1250, 0)));
        assertTrue(RoadmapService.cleared(DP, rated(1310, 0)));
    }

    @Test
    @DisplayName("rated: done needs the rating three-quarters up the band and a few solves there")
    void ratedDone() {
        assertFalse(RoadmapService.done(DP, rated(1400, 10)));
        assertTrue(RoadmapService.done(DP, rated(1430, 3)));
        // Rated well above the band, but nothing solved in it: the rating alone is not "done".
        assertFalse(RoadmapService.done(DP, rated(2000, 0)));
    }

    @Test
    @DisplayName("coverage: a few solves clear a prerequisite; done needs five reaching mid-band")
    void coverageRules() {
        assertFalse(RoadmapService.cleared(DP, coverage(2, 1300)));
        assertTrue(RoadmapService.cleared(DP, coverage(3, 1200)));
        assertFalse(RoadmapService.done(DP, coverage(8, 1300)));
        assertTrue(RoadmapService.done(DP, coverage(5, 1400)));
    }

    @Test
    @DisplayName("weakest first is read as how far into the band somebody is")
    void progress() {
        assertEquals(0.0, RoadmapService.progress(DP, rated(1100, 0)));
        assertEquals(0.5, RoadmapService.progress(DP, rated(1350, 0)), 1e-9);
        assertEquals(1.0, RoadmapService.progress(DP, rated(1700, 0)));
    }
}
