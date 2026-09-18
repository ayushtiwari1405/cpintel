package com.cpintel.service;

import com.cpintel.analytics.RecommendationEngine;
import com.cpintel.entity.Recommendation;
import com.cpintel.entity.RevisionSchedule;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.RecommendationRepository;
import com.cpintel.repository.jpa.RevisionScheduleRepository;
import com.cpintel.roadmap.RoadmapTaxonomy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecommendationService {

    private final RecommendationRepository recommendationRepository;
    private final RevisionScheduleRepository revisionScheduleRepository;
    private final RecommendationEngine recommendationEngine;
    private final ObjectMapper objectMapper;

    @Cacheable(value = "recommendations", key = "'daily:' + #userId")
    public RecommendationPayload getDaily(Long userId) {
        return getOrGenerate(userId, "DAILY", () -> recommendationEngine.generateDailySheet(userId));
    }

    @Cacheable(value = "recommendations", key = "'weekly:' + #userId")
    public RecommendationPayload getWeekly(Long userId) {
        return getOrGenerate(userId, "WEEKLY", () -> recommendationEngine.generateWeeklySheet(userId));
    }

    /**
     * What is due for revision, as something a page can render.
     *
     * <p>The raw entity used to be returned straight out. Its {@code topic} column now holds a
     * skill-tree node id rather than a display name, so a screen rendering it verbatim would
     * show the user "dp-bitmask". Mapping here also gives each item a way into the workspace,
     * which is the point of telling somebody a skill has gone stale.
     */
    public List<RevisionItem> getRevisionQueue(Long userId) {
        List<RevisionSchedule> due = revisionScheduleRepository.findDueRevisions(userId);
        if (due.isEmpty()) {
            recommendationEngine.generateRevisionSchedule(userId);
            due = revisionScheduleRepository.findDueRevisions(userId);
        }
        return due.stream().map(RecommendationService::toRevisionItem).toList();
    }

    private static RevisionItem toRevisionItem(RevisionSchedule rs) {
        RoadmapTaxonomy.NodeDef def = RoadmapTaxonomy.byId(rs.getTopic());
        return new RevisionItem(
            rs.getRevisionId(),
            rs.getTopic(),
            def == null ? rs.getTopic() : def.displayName(),
            def == null ? null : def.track(),
            def == null ? null : def.rollupTopic(),
            rs.getNextRevisionAt(),
            rs.getRevisionPriority(),
            rs.getDecayScore(),
            rs.getIntervalDays(),
            rs.getRepetitionCount(),
            rs.getEaseFactor(),
            rs.getLastRevisedAt(),
            // No specific problem named: the workspace opens on the skill itself and offers
            // its problems, which is the right granularity for "this has gone stale".
            def == null ? null : "/practice?node=" + def.id());
    }

    public void markRevisionDone(Long userId, Long revisionId) {
        RevisionSchedule rs = revisionScheduleRepository.findById(revisionId)
            .orElseThrow(() -> ApiException.notFound("Revision item not found"));
        if (!rs.getUser().getUserId().equals(userId))
            throw ApiException.forbidden("Not your revision item");

        // SM-2: increase interval and ease factor on success
        double newEase     = Math.min(2.5, rs.getEaseFactor() + 0.1);
        int    newInterval = Math.max(1, (int)(rs.getIntervalDays() * newEase));

        rs.setEaseFactor(newEase);
        rs.setIntervalDays(newInterval);
        rs.setRepetitionCount(rs.getRepetitionCount() + 1);
        rs.setLastRevisedAt(Instant.now());
        rs.setNextRevisionAt(Instant.now().plusSeconds(newInterval * 86_400L));
        rs.setDecayScore(Math.max(0, rs.getDecayScore() - 15));
        revisionScheduleRepository.save(rs);
    }

    private RecommendationPayload getOrGenerate(Long userId, String type, Runnable generator) {
        var existing = recommendationRepository.findLatestActiveByUserAndType(userId, type);
        if (existing.isEmpty()) {
            generator.run();
            existing = recommendationRepository.findLatestActiveByUserAndType(userId, type);
        }

        if (existing.isEmpty()) {
            return new RecommendationPayload(type, List.of(), Instant.now());
        }

        Recommendation rec = existing.get();
        List<Map<String, Object>> items = parseJson(rec.getProblemList());
        return new RecommendationPayload(type, items, rec.getGeneratedAt());
    }

    private List<Map<String, Object>> parseJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse recommendation JSON: {}", e.getMessage());
            return List.of();
        }
    }

    /** One overdue skill, named the way a person would recognise it. */
    public record RevisionItem(
        Long revisionId,
        String nodeKey,
        String title,
        String track,
        String topic,
        Instant nextRevisionAt,
        Integer revisionPriority,
        Double decayScore,
        Integer intervalDays,
        Integer repetitionCount,
        Double easeFactor,
        Instant lastRevisedAt,
        String practicePath
    ) {}

    public record RecommendationPayload(
        String type,
        List<Map<String, Object>> items,
        Instant generatedAt
    ) {}
}
