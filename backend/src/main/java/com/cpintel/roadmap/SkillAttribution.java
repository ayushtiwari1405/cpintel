package com.cpintel.roadmap;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Turns a user's practice history into per-node counts.
 *
 * <p>This is where two of the older engine's arithmetic bugs are fixed, and both mattered.
 *
 * <p><b>Distinct problems, not submissions.</b> The counts used to be incremented once per
 * submission per tag, so five wrong answers followed by an accepted one recorded six attempts
 * and one solve on a single problem — an accuracy of 17% for a problem the user got right. The
 * reverse also happened: re-submitting an already-accepted solution incremented {@code solved}
 * again, inflating the volume term. A problem is counted once here, whatever it took.
 *
 * <p><b>Every judge, not just Codeforces.</b> Attribution takes normalised tags, so LeetCode
 * and CodeChef history reaches the tree through {@link PlatformTagVocabulary} rather than being
 * silently discarded.
 *
 * <p>Everything in this class is a pure function over its inputs, so the attribution rules can
 * be tested without a database, a Spring context or a judge.
 */
public final class SkillAttribution {

    private SkillAttribution() {}

    /**
     * One problem the user has worked on, after submissions have been folded together.
     *
     * @param platform      judge the problem came from
     * @param problemKey    identity of the problem within that judge, used only for de-duplication
     * @param rating        difficulty on the Codeforces scale, or null when the judge gives none
     * @param cfTags        tags already translated into the Codeforces vocabulary
     * @param solved        whether any submission for it was accepted
     * @param lastAttemptAt most recent submission of any verdict
     */
    public record Practised(
        String platform,
        String problemKey,
        Integer rating,
        Set<String> cfTags,
        boolean solved,
        Instant lastAttemptAt
    ) {}

    /** What one node accumulated across the whole history. */
    public static final class NodeStat {
        private int solved;
        private int attempted;
        private Instant lastPractisedAt;

        public int solved() { return solved; }
        public int attempted() { return attempted; }
        public Instant lastPractisedAt() { return lastPractisedAt; }

        void record(Practised p) {
            attempted++;
            if (p.solved()) solved++;
            if (p.lastAttemptAt() != null
                && (lastPractisedAt == null || p.lastAttemptAt().isAfter(lastPractisedAt))) {
                lastPractisedAt = p.lastAttemptAt();
            }
        }
    }

    /**
     * Node ids a problem is evidence for.
     *
     * <p>With a known rating this is every node whose band contains it and whose tag rule it
     * satisfies. A problem legitimately belongs to more than one node — a 1900 DP-over-bitmasks
     * problem is evidence for bitmask DP and for subset enumeration alike — so this returns a
     * set rather than picking a winner.
     *
     * <p>With no rating at all, the band cannot discriminate, and matching on tags alone would
     * credit every node sharing the tag: one {@code data structures} problem of unknown
     * difficulty would count towards plain segment trees, lazy propagation, persistence and
     * segment tree beats at once. So the unrated case keeps only the <em>frontier</em> of the
     * matched set — a node is dropped when one of its own prerequisites also matched. That
     * credits the most elementary applicable skill and never the advanced ones, which is the
     * conservative direction to be wrong in.
     */
    public static Set<String> nodesFor(Integer rating, Set<String> cfTags) {
        if (cfTags == null || cfTags.isEmpty()) return Set.of();

        if (rating != null) {
            Set<String> matched = new LinkedHashSet<>();
            for (RoadmapTaxonomy.NodeDef node : RoadmapTaxonomy.NODES) {
                if (node.matches(rating, cfTags)) matched.add(node.id());
            }
            return matched;
        }

        Set<String> tagMatched = new LinkedHashSet<>();
        for (RoadmapTaxonomy.NodeDef node : RoadmapTaxonomy.NODES) {
            if (matchesTagsIgnoringBand(node, cfTags)) tagMatched.add(node.id());
        }
        return frontierOf(tagMatched);
    }

    /**
     * Fold a whole history into per-node counts.
     *
     * <p>Input is expected to already hold one entry per distinct problem; the de-duplication
     * itself belongs to the caller, which is the only place that knows how to identify a
     * problem on each judge.
     */
    public static Map<String, NodeStat> attribute(Collection<Practised> history) {
        Map<String, NodeStat> byNode = new LinkedHashMap<>();
        if (history == null) return byNode;

        for (Practised p : history) {
            for (String nodeId : nodesFor(p.rating(), p.cfTags())) {
                byNode.computeIfAbsent(nodeId, k -> new NodeStat()).record(p);
            }
        }
        return byNode;
    }

    /**
     * Roll node counts up to the coarse topics the radar chart is drawn from.
     *
     * <p>A problem attributed to several nodes in the same roll-up would otherwise be counted
     * several times in that topic, so the roll-up is recomputed from the history rather than
     * summed from the node totals.
     */
    public static Map<String, NodeStat> rollUp(Collection<Practised> history) {
        Map<String, NodeStat> byTopic = new LinkedHashMap<>();
        if (history == null) return byTopic;

        for (Practised p : history) {
            Set<String> topics = new LinkedHashSet<>();
            for (String nodeId : nodesFor(p.rating(), p.cfTags())) {
                RoadmapTaxonomy.NodeDef def = RoadmapTaxonomy.byId(nodeId);
                if (def != null) topics.add(def.rollupTopic());
            }
            for (String topic : topics) {
                byTopic.computeIfAbsent(topic, k -> new NodeStat()).record(p);
            }
        }
        return byTopic;
    }

    // ── internals ──────────────────────────────────────────────────────────

    private static boolean matchesTagsIgnoringBand(RoadmapTaxonomy.NodeDef node, Set<String> tags) {
        for (String required : node.allTags()) {
            if (!tags.contains(required)) return false;
        }
        if (node.anyTags().isEmpty()) return true;
        for (String candidate : node.anyTags()) {
            if (tags.contains(candidate)) return true;
        }
        return false;
    }

    /** Members of the set that have no prerequisite inside the same set. */
    private static Set<String> frontierOf(Set<String> matched) {
        Set<String> frontier = new LinkedHashSet<>();
        for (String id : matched) {
            RoadmapTaxonomy.NodeDef def = RoadmapTaxonomy.byId(id);
            if (def == null) continue;
            boolean hasPrereqInSet = false;
            for (String prereq : def.prereqIds()) {
                if (matched.contains(prereq)) { hasPrereqInSet = true; break; }
            }
            if (!hasPrereqInSet) frontier.add(id);
        }
        // A set whose every member depends on another member would collapse to nothing; that
        // cannot happen with an acyclic graph, but returning the input beats returning empty.
        return frontier.isEmpty() ? new HashSet<>(matched) : frontier;
    }
}
