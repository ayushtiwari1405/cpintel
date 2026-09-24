package com.cpintel.roadmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How a solved problem becomes evidence for a skill.
 *
 * <p>This is the join the whole tree rests on. If attribution is too loose, one problem credits
 * a dozen unrelated nodes and the tree tells the user nothing; if it is too tight, a user with
 * real history sees an empty map.
 */
class SkillAttributionTest {

    private static SkillAttribution.Practised solved(Integer rating, String... tags) {
        return new SkillAttribution.Practised(
            "CODEFORCES", rating + String.join("", tags), rating,
            Set.of(tags), true, Instant.now());
    }

    private static SkillAttribution.Practised attempted(Integer rating, String... tags) {
        return new SkillAttribution.Practised(
            "CODEFORCES", rating + String.join("", tags), rating,
            Set.of(tags), false, Instant.now());
    }

    @Nested
    @DisplayName("matching")
    class Matching {

        @Test
        @DisplayName("a problem outside a node's band is not evidence for it")
        void bandIsRespected() {
            // dp-basics is 1200-1500. An 800-rated implementation problem is not DP practice.
            Set<String> nodes = SkillAttribution.nodesFor(800, Set.of("dp"));
            assertFalse(nodes.contains("dp-basics"));
        }

        @Test
        @DisplayName("allTags separates the nodes that share a broad tag")
        void compoundTagsDiscriminate() {
            // Both of these are 1900-2300 and both carry the dp tag. Only the one that also
            // carries bitmasks is bitmask DP; without the all-of rule a single dp tag would
            // credit every advanced DP node at once and the tree would carry no information.
            Set<String> plainDp = SkillAttribution.nodesFor(2000, Set.of("dp"));
            Set<String> maskDp = SkillAttribution.nodesFor(2000, Set.of("dp", "bitmasks"));

            assertFalse(plainDp.contains("dp-bitmask"),
                "a problem with no bitmasks tag is not bitmask DP");
            assertTrue(maskDp.contains("dp-bitmask"));
        }

        @Test
        @DisplayName("a problem may legitimately be evidence for several skills")
        void attributionIsASet() {
            // A 2000-rated DP-over-bitmasks problem genuinely exercises both.
            Set<String> nodes = SkillAttribution.nodesFor(2000, Set.of("dp", "bitmasks"));
            assertTrue(nodes.size() > 1, "expected several matches, got " + nodes);
        }

        @Test
        @DisplayName("an untagged problem is evidence for nothing")
        void untaggedMatchesNothing() {
            assertTrue(SkillAttribution.nodesFor(1500, Set.of()).isEmpty());
            assertTrue(SkillAttribution.nodesFor(1500, null).isEmpty());
        }

        @Test
        @DisplayName("an unrated problem credits the most elementary matching skill, not the advanced ones")
        void unratedFallsBackToTheFrontier() {
            // With no difficulty the band cannot discriminate. Matching on tags
            // alone would let one unrated data-structures problem count towards plain segment
            // trees, lazy propagation, persistence and segment tree beats simultaneously.
            Set<String> nodes = SkillAttribution.nodesFor(null, Set.of("data structures"));

            assertFalse(nodes.isEmpty(), "an unrated problem should still count somewhere");
            assertFalse(nodes.contains("segment-tree-beats"),
                "an unrated problem must not credit a 2700+ specialist node");
            assertFalse(nodes.contains("segment-tree-persistent"),
                "an unrated problem must not credit a 2400+ specialist node");
        }

        @Test
        @DisplayName("the unrated frontier never contains a node whose prerequisite also matched")
        void frontierExcludesDependents() {
            Set<String> nodes = SkillAttribution.nodesFor(null, Set.of("dp"));
            for (String id : nodes) {
                RoadmapTaxonomy.NodeDef def = RoadmapTaxonomy.byId(id);
                assertNotNull(def);
                for (String prereq : def.prereqIds()) {
                    assertFalse(nodes.contains(prereq),
                        id + " kept alongside its prerequisite " + prereq);
                }
            }
        }
    }

    @Nested
    @DisplayName("aggregation")
    class Aggregation {

        @Test
        @DisplayName("counts solved and attempted separately")
        void countsBothOutcomes() {
            Map<String, SkillAttribution.NodeStat> stats = SkillAttribution.attribute(List.of(
                solved(1300, "dp"),
                solved(1350, "dp"),
                attempted(1400, "dp")));

            SkillAttribution.NodeStat dp = stats.get("dp-basics");
            assertNotNull(dp, "expected dp-basics in " + stats.keySet());
            assertEquals(3, dp.attempted());
            assertEquals(2, dp.solved());
        }

        @Test
        @DisplayName("keeps the most recent activity as the practice date")
        void tracksTheLatestPracticeDate() {
            Instant old = Instant.now().minus(200, ChronoUnit.DAYS);
            Instant recent = Instant.now().minus(3, ChronoUnit.DAYS);

            Map<String, SkillAttribution.NodeStat> stats = SkillAttribution.attribute(List.of(
                new SkillAttribution.Practised("CODEFORCES", "a", 1300, Set.of("dp"), true, old),
                new SkillAttribution.Practised("CODEFORCES", "b", 1300, Set.of("dp"), true, recent)));

            assertEquals(recent, stats.get("dp-basics").lastPractisedAt());
        }

        @Test
        @DisplayName("an empty history produces no rows rather than zeroed ones")
        void emptyHistoryIsEmpty() {
            assertTrue(SkillAttribution.attribute(List.of()).isEmpty());
            assertTrue(SkillAttribution.attribute(null).isEmpty());
        }
    }

    @Nested
    @DisplayName("roll-up")
    class RollUp {

        @Test
        @DisplayName("counts a problem once per topic however many of that topic's nodes it hits")
        void doesNotDoubleCountWithinATopic() {
            // A 2000-rated dp+bitmasks problem is evidence for several Dynamic Programming
            // nodes. Summing the node counts would record it as several solves in that topic;
            // the roll-up is recomputed from the history precisely to avoid that.
            Map<String, SkillAttribution.NodeStat> nodes =
                SkillAttribution.attribute(List.of(solved(2000, "dp", "bitmasks")));
            Map<String, SkillAttribution.NodeStat> topics =
                SkillAttribution.rollUp(List.of(solved(2000, "dp", "bitmasks")));

            long dpNodes = nodes.keySet().stream()
                .map(RoadmapTaxonomy::byId)
                .filter(d -> d != null && "Dynamic Programming".equals(d.rollupTopic()))
                .count();
            assertTrue(dpNodes > 1, "test needs a problem hitting several DP nodes");

            SkillAttribution.NodeStat dp = topics.get("Dynamic Programming");
            assertNotNull(dp);
            assertEquals(1, dp.solved(), "one problem is one solve in the topic");
            assertEquals(1, dp.attempted());
        }

        @Test
        @DisplayName("a problem spanning two topics counts once in each")
        void countsOncePerDistinctTopic() {
            Map<String, SkillAttribution.NodeStat> topics =
                SkillAttribution.rollUp(List.of(solved(2000, "dp", "bitmasks")));

            assertTrue(topics.size() >= 2, "expected several topics, got " + topics.keySet());
            for (SkillAttribution.NodeStat stat : topics.values()) {
                assertEquals(1, stat.solved());
            }
        }
    }
}
