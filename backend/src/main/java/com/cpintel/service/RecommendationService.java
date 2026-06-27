package com.cpintel.service;

import com.cpintel.analytics.OracleProcedureCaller;
import com.cpintel.entity.Recommendation;
import com.cpintel.entity.RevisionSchedule;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.RecommendationRepository;
import com.cpintel.repository.jpa.RevisionScheduleRepository;
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
    private final OracleProcedureCaller oracle;
    private final ObjectMapper objectMapper;

    @Cacheable(value = "recommendations", key = "'daily:' + #userId")
    public RecommendationPayload getDaily(Long userId) {
        return getOrGenerate(userId, "DAILY", () -> oracle.generateDailySheet(userId));
    }

    @Cacheable(value = "recommendations", key = "'weekly:' + #userId")
    public RecommendationPayload getWeekly(Long userId) {
        return getOrGenerate(userId, "WEEKLY", () -> oracle.generateWeeklySheet(userId));
    }

    public List<RevisionSchedule> getRevisionQueue(Long userId) {
        List<RevisionSchedule> due = revisionScheduleRepository.findDueRevisions(userId);
        if (due.isEmpty()) {
            oracle.generateRevisionSchedule(userId);
            due = revisionScheduleRepository.findDueRevisions(userId);
        }
        return due;
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

    public record RecommendationPayload(
        String type,
        List<Map<String, Object>> items,
        Instant generatedAt
    ) {}
}
