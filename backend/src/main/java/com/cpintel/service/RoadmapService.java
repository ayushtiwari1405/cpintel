package com.cpintel.service;

import com.cpintel.entity.RoadmapNode;
import com.cpintel.entity.TopicMastery;
import com.cpintel.entity.User;
import com.cpintel.entity.mongo.CfSubmission;
import com.cpintel.exception.ApiException;
import com.cpintel.integration.codeforces.CfModels;
import com.cpintel.integration.codeforces.CfProblemsetClient;
import com.cpintel.repository.jpa.RoadmapNodeRepository;
import com.cpintel.repository.jpa.TopicMasteryRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.repository.mongo.CfSubmissionRepository;
import com.cpintel.roadmap.RoadmapTaxonomy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoadmapService {

    private final RoadmapNodeRepository roadmapNodeRepository;
    private final TopicMasteryRepository topicMasteryRepository;
    private final UserRepository userRepository;
    private final CfProblemsetClient cfProblemsetClient;
    private final CfSubmissionRepository cfSubmissionRepository;

    private static final double UNLOCK_THRESHOLD = 35.0;
    private static final double COMPLETE_THRESHOLD = 75.0;

    public List<RoadmapNodeView> getRoadmap(Long userId) {
        ensureSeeded(userId);
        List<RoadmapNode> nodes = roadmapNodeRepository.findByUserUserIdOrderByOrderIndex(userId);
        Set<String> solvedProblemKeys = solvedProblemKeys(userId);

        Map<String, Integer> masteryByTopic = topicMasteryRepository.findByUserUserId(userId).stream()
            .collect(Collectors.toMap(TopicMastery::getTopic,
                tm -> (int) Math.round(tm.getMasteryScore() == null ? 0 : tm.getMasteryScore()),
                (a, b) -> a));

        List<CfModels.Submission.Problem> allProblems = cfProblemsetClient.getAllProblems();

        return nodes.stream()
            .map(n -> toView(n, allProblems, solvedProblemKeys, masteryByTopic))
            .collect(Collectors.toList());
    }

    @Transactional
    public List<RoadmapNodeView> regenerateRoadmap(Long userId) {
        ensureSeeded(userId);

        Map<String, Integer> masteryByTopic = topicMasteryRepository.findByUserUserId(userId).stream()
            .collect(Collectors.toMap(TopicMastery::getTopic,
                tm -> (int) Math.round(tm.getMasteryScore() == null ? 0 : tm.getMasteryScore()),
                (a, b) -> a));

        List<RoadmapNode> nodes = roadmapNodeRepository.findByUserUserIdOrderByOrderIndex(userId);
        Map<String, RoadmapNode> byKey = nodes.stream()
            .filter(n -> n.getNodeKey() != null)
            .collect(Collectors.toMap(RoadmapNode::getNodeKey, n -> n));

        for (RoadmapNode node : nodes) {
            var def = RoadmapTaxonomy.byId().get(node.getNodeKey());
            if (def == null) continue;

            int ownMastery = masteryByTopic.getOrDefault(node.getTopic(), 0);

            boolean prereqsMet = def.prereqIds().stream().allMatch(pid -> {
                RoadmapNode p = byKey.get(pid);
                if (p == null) return true;
                return "COMPLETED".equals(p.getStatus()) ||
                       (masteryByTopic.getOrDefault(p.getTopic(), 0) >= UNLOCK_THRESHOLD);
            });

            String newStatus;
            if (ownMastery >= COMPLETE_THRESHOLD) {
                newStatus = "COMPLETED";
            } else if (prereqsMet) {
                newStatus = ownMastery > 0 ? "IN_PROGRESS" : "UNLOCKED";
            } else {
                newStatus = "LOCKED";
            }

            if (!node.getStatus().equals(newStatus)) {
                node.setStatus(newStatus);
                if ("COMPLETED".equals(newStatus) && node.getCompletedAt() == null)
                    node.setCompletedAt(Instant.now());
                if (!"LOCKED".equals(newStatus) && node.getUnlockedAt() == null)
                    node.setUnlockedAt(Instant.now());
            }
        }

        roadmapNodeRepository.saveAll(nodes);
        log.info("Regenerated roadmap for user {} — {} nodes", userId, nodes.size());

        List<CfModels.Submission.Problem> allProblems = cfProblemsetClient.getAllProblems();
        Set<String> solvedKeys = solvedProblemKeys(userId);
        return nodes.stream()
            .map(n -> toView(n, allProblems, solvedKeys, masteryByTopic))
            .collect(Collectors.toList());
    }

    @Transactional
    public RoadmapNodeView markNodeProgress(Long userId, Long nodeId, String status) {
        RoadmapNode node = roadmapNodeRepository.findById(nodeId)
            .orElseThrow(() -> ApiException.notFound("Roadmap node not found"));
        if (!node.getUser().getUserId().equals(userId))
            throw ApiException.forbidden("Not your roadmap node");

        node.setStatus(status);
        if ("COMPLETED".equals(status) && node.getCompletedAt() == null)
            node.setCompletedAt(Instant.now());
        roadmapNodeRepository.save(node);

        return toView(node, cfProblemsetClient.getAllProblems(), solvedProblemKeys(userId), Map.of());
    }

    @Transactional
    public void ensureSeeded(Long userId) {
        long existing = roadmapNodeRepository.findByUserUserIdOrderByOrderIndex(userId).stream()
            .filter(n -> n.getNodeKey() != null).count();
        if (existing >= RoadmapTaxonomy.NODES.size()) return;

        User user = userRepository.findById(userId)
            .orElseThrow(() -> ApiException.notFound("User not found"));

        Map<String, RoadmapNode> already = roadmapNodeRepository.findByUserUserIdOrderByOrderIndex(userId)
            .stream().filter(n -> n.getNodeKey() != null)
            .collect(Collectors.toMap(RoadmapNode::getNodeKey, n -> n));

        List<RoadmapNode> toCreate = new ArrayList<>();
        for (var def : RoadmapTaxonomy.NODES) {
            if (already.containsKey(def.id())) continue;
            boolean noPrereqs = def.prereqIds().isEmpty();
            toCreate.add(RoadmapNode.builder()
                .user(user)
                .nodeKey(def.id())
                .topic(def.displayName())
                .parentTopic(def.parentTopic())
                .orderIndex(def.orderIndex())
                .minDifficulty(def.minDifficulty())
                .maxDifficulty(def.maxDifficulty())
                .prereqKeys(String.join(",", def.prereqIds()))
                .status(noPrereqs ? "UNLOCKED" : "LOCKED")
                .unlockedAt(noPrereqs ? Instant.now() : null)
                .build());
        }
        if (!toCreate.isEmpty()) {
            roadmapNodeRepository.saveAll(toCreate);
            log.info("Seeded {} roadmap nodes for user {}", toCreate.size(), userId);
        }
    }

    private Set<String> solvedProblemKeys(Long userId) {
        List<CfSubmission> subs = cfSubmissionRepository.findByUserId(userId);
        return subs.stream()
            .filter(s -> "OK".equals(s.getVerdict()) && s.getContestId() != null && s.getProblemIndex() != null)
            .map(s -> s.getContestId() + s.getProblemIndex())
            .collect(Collectors.toSet());
    }

    private RoadmapNodeView toView(
        RoadmapNode node,
        List<CfModels.Submission.Problem> allProblems,
        Set<String> solvedKeys,
        Map<String, Integer> masteryByTopic
    ) {
        var def = node.getNodeKey() != null ? RoadmapTaxonomy.byId().get(node.getNodeKey()) : null;
        List<RecommendedProblem> problems = List.of();

        if (def != null && !allProblems.isEmpty() && !"LOCKED".equals(node.getStatus())) {
            problems = allProblems.stream()
                .filter(p -> p.getRating() != null
                    && p.getRating() >= def.minDifficulty()
                    && p.getRating() <= def.maxDifficulty())
                .filter(p -> p.getTags() != null && def.cfTags().stream()
                    .anyMatch(tag -> p.getTags().stream().anyMatch(t -> t.equalsIgnoreCase(tag))))
                .filter(p -> p.getContestId() != null)
                .map(p -> {
                    String key = p.getContestId() + p.getIndex();
                    return new RecommendedProblem(
                        p.getContestId(), p.getIndex(), p.getName(), p.getRating(),
                        p.getTags(), solvedKeys.contains(key),
                        "https://codeforces.com/problemset/problem/" + p.getContestId() + "/" + p.getIndex()
                    );
                })
                .sorted(Comparator.comparing(RecommendedProblem::solved)
                    .thenComparing(rp -> rp.rating() == null ? 0 : rp.rating()))
                .limit(8)
                .collect(Collectors.toList());
        }

        return new RoadmapNodeView(
            node.getNodeId(), node.getNodeKey(), node.getTopic(), node.getParentTopic(),
            node.getStatus(), node.getOrderIndex(), node.getMinDifficulty(), node.getMaxDifficulty(),
            node.getPrereqKeys() == null || node.getPrereqKeys().isBlank()
                ? List.of() : List.of(node.getPrereqKeys().split(",")),
            node.getUnlockedAt(), node.getCompletedAt(), problems
        );
    }

    public record RecommendedProblem(
        Integer contestId, String index, String name, Integer rating,
        List<String> tags, boolean solved, String url
    ) {}

    public record RoadmapNodeView(
        Long nodeId, String nodeKey, String topic, String parentTopic,
        String status, Integer orderIndex, Integer minDifficulty, Integer maxDifficulty,
        List<String> prereqKeys, Instant unlockedAt, Instant completedAt,
        List<RecommendedProblem> problems
    ) {}
}
