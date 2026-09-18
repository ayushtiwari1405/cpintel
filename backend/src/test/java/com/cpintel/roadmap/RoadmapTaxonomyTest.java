package com.cpintel.roadmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The skill tree's structure, checked.
 *
 * <p>A hundred and forty nodes with hand-written prerequisite chains is exactly the kind of data
 * that rots quietly: a typo in a prerequisite id makes a node permanently unreachable, a cycle
 * makes a whole branch permanently locked, and neither shows up as an error anywhere. The tree
 * is the product's map of what to learn next, so it being well-formed is a correctness property,
 * not a style one.
 */
class RoadmapTaxonomyTest {

    @Test
    @DisplayName("every node id is unique")
    void idsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (RoadmapTaxonomy.NodeDef n : RoadmapTaxonomy.NODES) {
            assertTrue(seen.add(n.id()), "duplicate node id: " + n.id());
        }
        assertEquals(RoadmapTaxonomy.NODES.size(), RoadmapTaxonomy.BY_ID.size());
    }

    @Test
    @DisplayName("every prerequisite names a node that exists")
    void prerequisitesResolve() {
        List<String> broken = new ArrayList<>();
        for (RoadmapTaxonomy.NodeDef n : RoadmapTaxonomy.NODES) {
            for (String prereq : n.prereqIds()) {
                if (RoadmapTaxonomy.byId(prereq) == null) {
                    broken.add(n.id() + " -> " + prereq);
                }
            }
        }
        assertTrue(broken.isEmpty(), "unresolvable prerequisites: " + broken);
    }

    @Test
    @DisplayName("no node is its own prerequisite")
    void noSelfDependency() {
        for (RoadmapTaxonomy.NodeDef n : RoadmapTaxonomy.NODES) {
            assertFalse(n.prereqIds().contains(n.id()), n.id() + " depends on itself");
        }
    }

    @Test
    @DisplayName("the prerequisite graph is acyclic")
    void graphIsAcyclic() {
        // Kahn's algorithm. A cycle leaves nodes that never reach in-degree zero, and every node
        // in it stays LOCKED for every user forever with nothing to indicate why.
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();

        for (RoadmapTaxonomy.NodeDef n : RoadmapTaxonomy.NODES) {
            inDegree.putIfAbsent(n.id(), 0);
            for (String prereq : n.prereqIds()) {
                inDegree.merge(n.id(), 1, Integer::sum);
                dependents.computeIfAbsent(prereq, k -> new ArrayList<>()).add(n.id());
            }
        }

        Deque<String> ready = new ArrayDeque<>();
        inDegree.forEach((id, degree) -> { if (degree == 0) ready.add(id); });

        int settled = 0;
        while (!ready.isEmpty()) {
            String id = ready.poll();
            settled++;
            for (String dependent : dependents.getOrDefault(id, List.of())) {
                if (inDegree.merge(dependent, -1, Integer::sum) == 0) ready.add(dependent);
            }
        }

        assertEquals(RoadmapTaxonomy.NODES.size(), settled,
            "prerequisite cycle: " + inDegree.entrySet().stream()
                .filter(e -> e.getValue() > 0).map(Map.Entry::getKey).toList());
    }

    @Test
    @DisplayName("declaration order is a topological order")
    void declarationOrderIsTopological() {
        // regenerateRoadmap propagates unlocks in one forward pass over NODES, so a node
        // declared before one of its prerequisites would need a second pass to unlock and the
        // user would have to press "update from progress" twice to walk down the tree.
        Set<String> declared = new HashSet<>();
        for (RoadmapTaxonomy.NodeDef n : RoadmapTaxonomy.NODES) {
            for (String prereq : n.prereqIds()) {
                assertTrue(declared.contains(prereq),
                    n.id() + " is declared before its prerequisite " + prereq);
            }
            declared.add(n.id());
        }
    }

    @Test
    @DisplayName("at least one node is reachable with no prerequisites")
    void hasRoots() {
        assertTrue(RoadmapTaxonomy.NODES.stream().anyMatch(n -> n.prereqIds().isEmpty()),
            "a tree with no roots is entirely locked for a new user");
    }

    @Test
    @DisplayName("every node declares a known track and a roll-up topic")
    void groupingsAreWellFormed() {
        for (RoadmapTaxonomy.NodeDef n : RoadmapTaxonomy.NODES) {
            assertTrue(RoadmapTaxonomy.TRACKS.contains(n.track()),
                n.id() + " has unknown track " + n.track());
            assertNotNull(n.rollupTopic(), n.id() + " has no roll-up topic");
            assertFalse(n.rollupTopic().isBlank(), n.id() + " has a blank roll-up topic");
        }
        assertEquals(RoadmapTaxonomy.TRACKS.size(), RoadmapTaxonomy.BY_TRACK.size());
    }

    @Test
    @DisplayName("every node has a usable difficulty band")
    void bandsAreSane() {
        for (RoadmapTaxonomy.NodeDef n : RoadmapTaxonomy.NODES) {
            assertTrue(n.minRating() < n.maxRating(),
                n.id() + " has an inverted or empty band");
            assertTrue(n.minRating() >= 800,
                n.id() + " starts below the lowest Codeforces rating");
            assertTrue(n.maxRating() <= 3500,
                n.id() + " runs past the top of the Codeforces scale");
        }
    }

    @Test
    @DisplayName("every node can match something: it declares at least one tag")
    void everyNodeHasTags() {
        for (RoadmapTaxonomy.NodeDef n : RoadmapTaxonomy.NODES) {
            assertFalse(n.anyTags().isEmpty() && n.allTags().isEmpty(),
                n.id() + " declares no tags, so no problem can ever be attributed to it");
        }
    }

    @Test
    @DisplayName("only real Codeforces tags are used")
    void tagsAreRealCodeforcesTags() {
        // Codeforces' published tag vocabulary. A tag outside it matches no problem, so the node
        // renders permanently empty and the mistake is invisible until someone opens that node.
        Set<String> known = Set.of(
            "2-sat", "binary search", "bitmasks", "brute force", "chinese remainder theorem",
            "combinatorics", "constructive algorithms", "data structures", "dfs and similar",
            "divide and conquer", "dp", "dsu", "expression parsing", "fft", "flows", "games",
            "geometry", "graph matchings", "graphs", "greedy", "hashing", "implementation",
            "interactive", "math", "matrices", "meet-in-the-middle", "number theory",
            "probabilities", "schedules", "shortest paths", "sortings",
            "string suffix structures", "strings", "ternary search", "trees", "two pointers");

        List<String> unknown = new ArrayList<>();
        for (RoadmapTaxonomy.NodeDef n : RoadmapTaxonomy.NODES) {
            for (String tag : n.anyTags()) {
                if (!known.contains(tag)) unknown.add(n.id() + ": " + tag);
            }
            for (String tag : n.allTags()) {
                if (!known.contains(tag)) unknown.add(n.id() + ": " + tag);
            }
        }
        assertTrue(unknown.isEmpty(), "tags Codeforces does not publish: " + unknown);
    }

    @Test
    @DisplayName("order indices are dense and start at one")
    void orderIndicesAreContiguous() {
        for (int i = 0; i < RoadmapTaxonomy.NODES.size(); i++) {
            assertEquals(i + 1, RoadmapTaxonomy.NODES.get(i).orderIndex(),
                "order index drifted at " + RoadmapTaxonomy.NODES.get(i).id());
        }
    }

    @Test
    @DisplayName("every node id fits the column that stores it")
    void idsFitTheSchema() {
        // roadmap_nodes.node_key is VARCHAR(60) and topic_mastery.topic is VARCHAR(120).
        for (RoadmapTaxonomy.NodeDef n : RoadmapTaxonomy.NODES) {
            assertTrue(n.id().length() <= 60, "node id too long for the column: " + n.id());
            assertTrue(n.displayName().length() <= 100,
                "display name too long for the column: " + n.displayName());
        }
    }

    @Test
    @DisplayName("node ids and roll-up topics cannot collide in the mastery table")
    void namespacesDoNotCollide() {
        // Both are stored in topic_mastery.topic under one unique constraint, so a node id that
        // equalled a roll-up name would make the two rows fight over the same row.
        Set<String> rollups = new HashSet<>(RoadmapTaxonomy.rollupTopics());
        for (RoadmapTaxonomy.NodeDef n : RoadmapTaxonomy.NODES) {
            assertFalse(rollups.contains(n.id()),
                "node id collides with a roll-up topic name: " + n.id());
        }
    }

    @Test
    @DisplayName("every node explains itself")
    void everyNodeHasABlurb() {
        for (RoadmapTaxonomy.NodeDef n : RoadmapTaxonomy.NODES) {
            assertNotNull(n.blurb(), n.id() + " has no blurb");
            assertFalse(n.blurb().isBlank(), n.id() + " has a blank blurb");
        }
    }
}
