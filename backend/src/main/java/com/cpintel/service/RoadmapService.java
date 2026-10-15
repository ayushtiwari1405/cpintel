package com.cpintel.service;

import com.cpintel.entity.RoadmapNode;
import com.cpintel.entity.TopicMastery;
import com.cpintel.entity.User;
import com.cpintel.entity.mongo.CfSubmission;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.RoadmapNodeRepository;
import com.cpintel.repository.jpa.TopicMasteryRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.repository.mongo.CfSubmissionRepository;
import com.cpintel.roadmap.ProblemRecommender;
import com.cpintel.roadmap.RoadmapTaxonomy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The skill tree: what is unlocked, what is next, and what to solve in each node.
 *
 * <h2>The join that was wrong</h2>
 *
 * <p>Unlock status is decided by how much mastery the user has in a node and in its
 * prerequisites. That lookup used to read {@code masteryByTopic.get(node.getTopic())} - but
 * {@code node.topic} holds the node's <em>display name</em> ("DP Basics (1D)", "Array Basics")
 * while mastery was keyed by coarse topic name ("Dynamic Programming", "Arrays"). The two
 * strings matched only for the handful of nodes whose display name happened to equal a topic
 * name. Every other node read a mastery of exactly zero, which meant it never satisfied a
 * prerequisite and never completed: the tree stayed almost entirely {@code LOCKED} no matter
 * how much the user solved, and the "update from progress" button appeared to do nothing.
 *
 * <p>Mastery is now kept per node under the node's own stable id, so the lookup is on the key
 * the row is actually written with. The coarse topics still exist for the radar, derived from
 * the same nodes rather than counted separately.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoadmapService {

    private final RoadmapNodeRepository roadmapNodeRepository;
    private final TopicMasteryRepository topicMasteryRepository;
    private final UserRepository userRepository;
    private final CfSubmissionRepository cfSubmissionRepository;
    private final ProblemRecommender problemRecommender;

    /*
     * When a node opens and when it is done.
     *
     * With a skill rating (Codeforces or gauntlet): a prerequisite is cleared once its rating
     * reaches a third of the way into its band, and a node is done at three-quarters of the way
     * up — someone rated there solves the band's harder problems in a round about as often as
     * not — provided they have also solved a few of its problems. The rating alone could be the
     * person's overall rating with nothing behind it, and "done" should rest on something they
     * did here.
     *
     * Without one (coverage): a prerequisite is cleared by a few solves in it, and a node is done
     * after five solves of which the hardest reaches the middle of its band.
     */
    static final double UNLOCK_SHARE = 0.35;
    static final double COMPLETE_SHARE = 0.75;
    static final int COMPLETE_MIN_SOLVED = 3;
    static final int COVERAGE_UNLOCK_SOLVED = 3;
    static final int COVERAGE_COMPLETE_SOLVED = 5;

    /** Problems offered per node in the detail panel. */
    private static final int PROBLEMS_PER_NODE = 10;

    /** Nodes suggested by {@link #nextUp}. */
    private static final int NEXT_UP_COUNT = 6;

    @Transactional
    public List<RoadmapNodeView> getRoadmap(Long userId) {
        ensureSeeded(userId);
        Map<String, TopicMastery> mastery = masteryByNodeKey(userId);
        Set<String> solved = solvedProblemKeys(userId);

        return roadmapNodeRepository.findByUserUserIdOrderByOrderIndex(userId).stream()
            .map(n -> toView(n, mastery, solved))
            .collect(Collectors.toList());
    }

    /**
     * Recompute every node's status from current mastery, then return the tree.
     *
     * <p>Unlocking cascades: a node whose prerequisites clear on this pass becomes available in
     * the same pass rather than the next one, so a user who has just synced a large history does
     * not have to press the button repeatedly to walk down the tree.
     */
    @Transactional
    public List<RoadmapNodeView> regenerateRoadmap(Long userId) {
        ensureSeeded(userId);

        Map<String, TopicMastery> mastery = masteryByNodeKey(userId);
        List<RoadmapNode> nodes = roadmapNodeRepository.findByUserUserIdOrderByOrderIndex(userId);
        Map<String, RoadmapNode> byKey = nodes.stream()
            .filter(n -> n.getNodeKey() != null)
            .collect(Collectors.toMap(RoadmapNode::getNodeKey, n -> n, (a, b) -> a));

        // Declaration order is a topological order of the tree - every node is declared after
        // its prerequisites - so a single forward pass propagates unlocks all the way down.
        for (RoadmapTaxonomy.NodeDef def : RoadmapTaxonomy.NODES) {
            RoadmapNode node = byKey.get(def.id());
            if (node == null) continue;

            String newStatus = statusFor(def, node, byKey, mastery);
            if (!newStatus.equals(node.getStatus())) {
                node.setStatus(newStatus);
                if ("COMPLETED".equals(newStatus) && node.getCompletedAt() == null) {
                    node.setCompletedAt(Instant.now());
                }
                if (!"LOCKED".equals(newStatus) && node.getUnlockedAt() == null) {
                    node.setUnlockedAt(Instant.now());
                }
            }
        }

        roadmapNodeRepository.saveAll(nodes);
        log.info("Regenerated roadmap for user {} - {} nodes", userId, nodes.size());

        Set<String> solved = solvedProblemKeys(userId);
        return nodes.stream().map(n -> toView(n, mastery, solved)).collect(Collectors.toList());
    }

    /**
     * The handful of nodes worth working on right now: unlocked or in progress, weakest first,
     * each with problems attached.
     *
     * <p>A hundred and forty nodes is a good map and a bad to-do list. This is the to-do list,
     * and it is what the practice workspace shows when it is opened without a problem chosen.
     */
    @Transactional
    public List<RoadmapNodeView> nextUp(Long userId) {
        ensureSeeded(userId);
        Map<String, TopicMastery> mastery = masteryByNodeKey(userId);
        Set<String> solved = solvedProblemKeys(userId);

        return roadmapNodeRepository.findByUserUserIdOrderByOrderIndex(userId).stream()
            .filter(n -> "UNLOCKED".equals(n.getStatus()) || "IN_PROGRESS".equals(n.getStatus()))
            .sorted(Comparator
                .comparingDouble((RoadmapNode n) -> progress(
                    RoadmapTaxonomy.byId(n.getNodeKey()), mastery.get(n.getNodeKey())))
                .thenComparing(RoadmapNode::getOrderIndex))
            .limit(NEXT_UP_COUNT)
            .map(n -> toView(n, mastery, solved))
            .collect(Collectors.toList());
    }

    /**
     * One node by its stable key.
     *
     * <p>Exists so the practice workspace can say which skill a problem was suggested for, and
     * offer the rest of that skill's problems, without pulling the whole hundred-and-forty-node
     * tree and its fourteen hundred attached problems down just to read one name.
     */
    @Transactional
    public RoadmapNodeView nodeByKey(Long userId, String nodeKey) {
        if (RoadmapTaxonomy.byId(nodeKey) == null) {
            throw ApiException.notFound("Unknown skill: " + nodeKey);
        }
        ensureSeeded(userId);

        RoadmapNode node = roadmapNodeRepository.findByUserUserIdOrderByOrderIndex(userId).stream()
            .filter(n -> nodeKey.equals(n.getNodeKey()))
            .findFirst()
            .orElseThrow(() -> ApiException.notFound("Skill not on your roadmap: " + nodeKey));

        return toView(node, masteryByNodeKey(userId), solvedProblemKeys(userId));
    }

    @Transactional
    public RoadmapNodeView markNodeProgress(Long userId, Long nodeId, String status) {
        RoadmapNode node = roadmapNodeRepository.findById(nodeId)
            .orElseThrow(() -> ApiException.notFound("Roadmap node not found"));
        if (!node.getUser().getUserId().equals(userId)) {
            throw ApiException.forbidden("Not your roadmap node");
        }
        if (!List.of("LOCKED", "UNLOCKED", "IN_PROGRESS", "COMPLETED").contains(status)) {
            throw ApiException.badRequest("Unknown roadmap status: " + status);
        }

        node.setStatus(status);
        if ("COMPLETED".equals(status) && node.getCompletedAt() == null) {
            node.setCompletedAt(Instant.now());
        }
        if (!"LOCKED".equals(status) && node.getUnlockedAt() == null) {
            node.setUnlockedAt(Instant.now());
        }
        roadmapNodeRepository.save(node);

        return toView(node, masteryByNodeKey(userId), solvedProblemKeys(userId));
    }

    /**
     * Create any node rows the user is missing, and retire ones the taxonomy no longer has.
     *
     * <p>Both halves matter because the tree grows. Seeding only ever added rows, so a user
     * created before a node existed simply never got it; and a node that is removed or renamed
     * left a row behind that the UI would keep rendering with no definition to draw it from.
     */
    @Transactional
    public void ensureSeeded(Long userId) {
        List<RoadmapNode> existing = roadmapNodeRepository.findByUserUserIdOrderByOrderIndex(userId);
        Map<String, RoadmapNode> byKey = existing.stream()
            .filter(n -> n.getNodeKey() != null)
            .collect(Collectors.toMap(RoadmapNode::getNodeKey, n -> n, (a, b) -> a));

        List<RoadmapNode> retired = existing.stream()
            .filter(n -> n.getNodeKey() == null || RoadmapTaxonomy.byId(n.getNodeKey()) == null)
            .toList();

        List<RoadmapNode> toCreate = new ArrayList<>();
        List<RoadmapNode> toUpdate = new ArrayList<>();

        for (RoadmapTaxonomy.NodeDef def : RoadmapTaxonomy.NODES) {
            RoadmapNode node = byKey.get(def.id());
            if (node == null) {
                boolean noPrereqs = def.prereqIds().isEmpty();
                toCreate.add(RoadmapNode.builder()
                    .user(userRepository.getReferenceById(userId))
                    .nodeKey(def.id())
                    .topic(def.displayName())
                    .parentTopic(def.rollupTopic())
                    .orderIndex(def.orderIndex())
                    .minDifficulty(def.minRating())
                    .maxDifficulty(def.maxRating())
                    .prereqKeys(String.join(",", def.prereqIds()))
                    .status(noPrereqs ? "UNLOCKED" : "LOCKED")
                    .unlockedAt(noPrereqs ? Instant.now() : null)
                    .build());
            } else if (definitionDrifted(node, def)) {
                // The taxonomy is edited far more often than a user's progress, so a node whose
                // band, name or prerequisites changed is brought back in line rather than left
                // describing a version of the tree that no longer exists. Status and the two
                // timestamps are the user's and are never touched here.
                node.setTopic(def.displayName());
                node.setParentTopic(def.rollupTopic());
                node.setOrderIndex(def.orderIndex());
                node.setMinDifficulty(def.minRating());
                node.setMaxDifficulty(def.maxRating());
                node.setPrereqKeys(String.join(",", def.prereqIds()));
                toUpdate.add(node);
            }
        }

        if (!retired.isEmpty()) {
            roadmapNodeRepository.deleteAll(retired);
            log.info("Retired {} roadmap nodes no longer in the taxonomy for user {}",
                retired.size(), userId);
        }
        if (!toUpdate.isEmpty()) roadmapNodeRepository.saveAll(toUpdate);
        if (!toCreate.isEmpty()) {
            roadmapNodeRepository.saveAll(toCreate);
            if (userRepository.findById(userId).isEmpty()) {
                throw ApiException.notFound("User not found");
            }
            log.info("Seeded {} roadmap nodes for user {}", toCreate.size(), userId);
        }
    }

    // -- internals ---------------------------------------------------------

    private String statusFor(RoadmapTaxonomy.NodeDef def, RoadmapNode node,
                             Map<String, RoadmapNode> byKey, Map<String, TopicMastery> mastery) {
        // A status the user set by hand is theirs to keep; nothing here overrides it downwards.
        TopicMastery own = mastery.get(def.id());

        boolean prereqsMet = def.prereqIds().stream().allMatch(pid -> {
            RoadmapNode prereq = byKey.get(pid);
            if (prereq == null) return true;
            return "COMPLETED".equals(prereq.getStatus())
                || cleared(RoadmapTaxonomy.byId(pid), mastery.get(pid));
        });

        if (done(def, own)) return "COMPLETED";
        // Done stays done, however it got there — by rating, by hand, or by the placement
        // gauntlet. This used to return UNLOCKED for a completed node whose own mastery was
        // low, which undid every hand-marked and placed completion on the next regenerate.
        if ("COMPLETED".equals(node.getStatus())) return "COMPLETED";
        if (!prereqsMet) {
            // Never re-lock something already opened; that reads as lost progress.
            return node.getStatus() == null ? "LOCKED" : node.getStatus();
        }
        return attempted(own) > 0 ? "IN_PROGRESS" : "UNLOCKED";
    }

    /** Whether a prerequisite has been got far enough into to open what depends on it. */
    static boolean cleared(RoadmapTaxonomy.NodeDef def, TopicMastery tm) {
        if (def == null || tm == null) return false;
        if (tm.getSkillRating() != null) {
            return tm.getSkillRating() >= bandPoint(def, UNLOCK_SHARE);
        }
        return solved(tm) >= COVERAGE_UNLOCK_SOLVED;
    }

    static boolean done(RoadmapTaxonomy.NodeDef def, TopicMastery tm) {
        if (def == null || tm == null) return false;
        if (tm.getSkillRating() != null) {
            return tm.getSkillRating() >= bandPoint(def, COMPLETE_SHARE)
                && solved(tm) >= COMPLETE_MIN_SOLVED;
        }
        return solved(tm) >= COVERAGE_COMPLETE_SOLVED
            && tm.getMaxSolvedRating() != null
            && tm.getMaxSolvedRating() >= bandPoint(def, 0.5);
    }

    /** How far along a node someone is, 0 to 1: the order "weakest first" is read in. */
    static double progress(RoadmapTaxonomy.NodeDef def, TopicMastery tm) {
        if (def == null || tm == null) return 0;
        if (tm.getSkillRating() != null) {
            double span = Math.max(1, def.maxRating() - def.minRating());
            return Math.max(0, Math.min(1, (tm.getSkillRating() - def.minRating()) / span));
        }
        return Math.min(1.0, solved(tm) / (double) COVERAGE_COMPLETE_SOLVED);
    }

    private static double bandPoint(RoadmapTaxonomy.NodeDef def, double share) {
        return def.minRating() + share * (def.maxRating() - def.minRating());
    }

    private static int solved(TopicMastery tm) {
        return tm.getProblemsSolved() == null ? 0 : tm.getProblemsSolved();
    }

    private static int attempted(TopicMastery tm) {
        return tm == null || tm.getProblemsAttempted() == null ? 0 : tm.getProblemsAttempted();
    }

    private boolean definitionDrifted(RoadmapNode node, RoadmapTaxonomy.NodeDef def) {
        return !def.displayName().equals(node.getTopic())
            || !def.rollupTopic().equals(node.getParentTopic())
            || !Integer.valueOf(def.orderIndex()).equals(node.getOrderIndex())
            || !Integer.valueOf(def.minRating()).equals(node.getMinDifficulty())
            || !Integer.valueOf(def.maxRating()).equals(node.getMaxDifficulty())
            || !String.join(",", def.prereqIds()).equals(
                   node.getPrereqKeys() == null ? "" : node.getPrereqKeys());
    }

    /** NODE-scope mastery rows, keyed by the node id they were written under. */
    private Map<String, TopicMastery> masteryByNodeKey(Long userId) {
        Map<String, TopicMastery> byKey = new LinkedHashMap<>();
        for (TopicMastery tm : topicMasteryRepository
                .findByUserUserIdAndScope(userId, TopicMastery.Scope.NODE)) {
            byKey.put(tm.getTopic(), tm);
        }
        return byKey;
    }

    private double masteryScore(Map<String, TopicMastery> mastery, String nodeKey) {
        TopicMastery tm = nodeKey == null ? null : mastery.get(nodeKey);
        return tm == null || tm.getMasteryScore() == null ? 0.0 : tm.getMasteryScore();
    }

    private double confidenceScore(Map<String, TopicMastery> mastery, String nodeKey) {
        TopicMastery tm = nodeKey == null ? null : mastery.get(nodeKey);
        return tm == null || tm.getConfidenceScore() == null ? 0.0 : tm.getConfidenceScore();
    }

    private Set<String> solvedProblemKeys(Long userId) {
        return cfSubmissionRepository.findByUserId(userId).stream()
            .filter(s -> "OK".equals(s.getVerdict())
                && s.getContestId() != null && s.getProblemIndex() != null)
            .map(s -> s.getContestId() + s.getProblemIndex())
            .collect(Collectors.toSet());
    }

    private RoadmapNodeView toView(RoadmapNode node, Map<String, TopicMastery> mastery,
                                   Set<String> solvedKeys) {
        RoadmapTaxonomy.NodeDef def = RoadmapTaxonomy.byId(node.getNodeKey());
        TopicMastery tm = node.getNodeKey() == null ? null : mastery.get(node.getNodeKey());

        double masteryScore = tm == null || tm.getMasteryScore() == null ? 0 : tm.getMasteryScore();

        List<ProblemRecommender.Recommended> problems = List.of();
        if (def != null && !"LOCKED".equals(node.getStatus())) {
            problems = problemRecommender.forNode(def, tm == null ? null : tm.getSkillRating(),
                masteryScore, solvedKeys, PROBLEMS_PER_NODE);
        }

        return new RoadmapNodeView(
            node.getNodeId(),
            node.getNodeKey(),
            node.getTopic(),
            node.getParentTopic(),
            def == null ? null : def.track(),
            def == null ? null : def.blurb(),
            node.getStatus(),
            node.getOrderIndex(),
            node.getMinDifficulty(),
            node.getMaxDifficulty(),
            node.getPrereqKeys() == null || node.getPrereqKeys().isBlank()
                ? List.of() : List.of(node.getPrereqKeys().split(",")),
            masteryScore,
            tm == null || tm.getConfidenceScore() == null ? 0 : tm.getConfidenceScore(),
            tm == null || tm.getDecayScore() == null ? 0 : tm.getDecayScore(),
            tm == null || tm.getProblemsSolved() == null ? 0 : tm.getProblemsSolved(),
            tm == null || tm.getProblemsAttempted() == null ? 0 : tm.getProblemsAttempted(),
            tm == null ? null : tm.getLastPracticedAt(),
            node.getUnlockedAt(),
            node.getCompletedAt(),
            tm == null ? null : tm.getSkillRating(),
            tm == null ? null : tm.getSkillSd(),
            tm == null || tm.getSkillProblems() == null ? 0 : tm.getSkillProblems(),
            tm == null ? null : tm.getSkillBasis(),
            tm == null ? null : tm.getMaxSolvedRating(),
            problems
        );
    }

    /** One node as the UI needs it: where it sits, how the user is doing, and what to solve. */
    public record RoadmapNodeView(
        Long nodeId,
        String nodeKey,
        String topic,
        String parentTopic,
        String track,
        String blurb,
        String status,
        Integer orderIndex,
        Integer minDifficulty,
        Integer maxDifficulty,
        List<String> prereqKeys,
        double masteryScore,
        double confidenceScore,
        double decayScore,
        int problemsSolved,
        int problemsAttempted,
        Instant lastPracticedAt,
        Instant unlockedAt,
        Instant completedAt,
        /** Rating on this skill in Codeforces points; null when coverage is shown instead. */
        Double skillRating,
        Double skillSd,
        int skillProblems,
        /** CODEFORCES or GAUNTLET: where skillRating came from. */
        String skillBasis,
        Integer maxSolvedRating,
        List<ProblemRecommender.Recommended> problems
    ) {}
}
