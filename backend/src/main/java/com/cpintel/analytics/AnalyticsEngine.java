package com.cpintel.analytics;

import com.cpintel.entity.ContestSummary;
import com.cpintel.entity.TopicMastery;
import com.cpintel.repository.jpa.ContestSummaryRepository;
import com.cpintel.repository.jpa.TopicMasteryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Scores whatever rows the attribution pass wrote: mastery, retention, decay and confidence per
 * skill, plus the contest-derived accuracy and consistency figures.
 *
 * <p>The arithmetic lives in {@link ScoringFormulas}; this class only supplies it with data and
 * writes the results back. It is deliberately indifferent to whether a row is one skill-tree
 * node or one coarse roll-up - both are scored the same way, from the same columns.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsEngine {

    private final TopicMasteryRepository topicMasteryRepository;
    private final ContestSummaryRepository contestSummaryRepository;
    private final UnifiedRatingService unifiedRatingService;

    /** Mastery for one skill, 0-100. Returns 0 if the user has no row for it. */
    @Transactional(readOnly = true)
    public double calculateMastery(Long userId, String topic) {
        return topicMasteryRepository.findByUserUserIdAndTopic(userId, topic)
            .map(this::masteryOf)
            .orElse(0.0);
    }

    /**
     * Share of contests on a platform where the user gained rating, 0-100.
     *
     * @param platform a platform name, or {@code "ALL"} for every platform.
     */
    @Transactional(readOnly = true)
    public double calculateAccuracy(Long userId, String platform) {
        return accuracyOf(contestsFor(userId, platform));
    }

    /** Share of contests in an already-loaded list where the user gained rating, 0-100. */
    public double accuracyOf(List<ContestSummary> contests) {
        int gains = (int) contests.stream()
            .filter(c -> c.getRatingChange() != null && c.getRatingChange() > 0)
            .count();
        return ScoringFormulas.accuracy(contests.size(), gains);
    }

    /**
     * How steady the user's contest results are, 0-100.
     *
     * <p>Computed per platform and then averaged, rather than over every contest at once, since
     * judges do not share rating scales. Only Codeforces is synced today.
     */
    @Transactional(readOnly = true)
    public double calculateConsistency(Long userId) {
        return consistencyOf(contestSummaryRepository.findByUserUserIdOrderByContestDateDesc(userId));
    }

    /** Consistency from an already-loaded contest list, grouped by platform internally. */
    public double consistencyOf(List<ContestSummary> contests) {
        Map<String, List<Integer>> byPlatform = new LinkedHashMap<>();
        for (ContestSummary c : contests) {
            if (c.getRatingChange() == null) continue;
            byPlatform.computeIfAbsent(c.getPlatform(), k -> new ArrayList<>())
                .add(c.getRatingChange());
        }

        List<Double> scores = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : byPlatform.entrySet()) {
            if (e.getValue().size() >= ScoringFormulas.CONSISTENCY_MIN_CONTESTS) {
                scores.add(ScoringFormulas.consistency(e.getValue(), e.getKey()));
            }
        }

        if (scores.isEmpty()) return ScoringFormulas.CONSISTENCY_UNKNOWN;
        return ScoringFormulas.round2(
            scores.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    /** How much of a skill's mastery has faded through inactivity. */
    @Transactional(readOnly = true)
    public double calculateDecay(Long masteryId) {
        return topicMasteryRepository.findById(masteryId)
            .map(this::decayOf)
            .orElse(0.0);
    }

    /** How much the mastery figure for a skill can be trusted, based on sample size. */
    @Transactional(readOnly = true)
    public double calculateConfidence(Long userId, String topic) {
        return topicMasteryRepository.findByUserUserIdAndTopic(userId, topic)
            .map(tm -> ScoringFormulas.confidence(orZero(tm.getProblemsAttempted())))
            .orElse(0.0);
    }

    /**
     * Recompute mastery, decay, confidence and revision score for every skill the user has, then
     * refresh their unified score.
     *
     * <p>The Oracle procedure updated only unified_score here and left the per-platform cf/lc/cc
     * components stale, because the function it called had been made read-only to get the DML
     * out of it. This writes the whole row.
     */
    @Transactional
    public void refreshAllMastery(Long userId) {
        List<TopicMastery> rows = topicMasteryRepository.findByUserUserId(userId);

        for (TopicMastery tm : rows) {
            double mastery    = masteryOf(tm);
            double decay      = ScoringFormulas.decay(mastery, daysSincePractice(tm));
            double confidence = ScoringFormulas.confidence(orZero(tm.getProblemsAttempted()));

            tm.setMasteryScore(mastery);
            tm.setDecayScore(decay);
            tm.setConfidenceScore(confidence);
            tm.setRevisionScore(ScoringFormulas.revisionScore(mastery, decay));
            tm.setComputedAt(Instant.now());
        }
        topicMasteryRepository.saveAll(rows);

        unifiedRatingService.updateScoreForUser(userId);
        log.debug("Refreshed mastery for user {} across {} rows", userId, rows.size());
    }

    // -- internals ---------------------------------------------------------

    private double masteryOf(TopicMastery tm) {
        return ScoringFormulas.mastery(
            orZero(tm.getProblemsSolved()),
            orZero(tm.getProblemsAttempted()));
    }

    /**
     * Decay is computed against freshly recalculated mastery rather than the stored column, so a
     * stale mastery_score cannot drag the decay figure with it.
     */
    private double decayOf(TopicMastery tm) {
        return ScoringFormulas.decay(masteryOf(tm), daysSincePractice(tm));
    }

    private List<ContestSummary> contestsFor(Long userId, String platform) {
        return "ALL".equals(platform)
            ? contestSummaryRepository.findByUserUserIdOrderByContestDateDesc(userId)
            : contestSummaryRepository.findByUserUserIdAndPlatform(userId, platform);
    }

    /**
     * Whole days since the skill was last practised, rounded to nearest.
     *
     * <p>A skill that has never been practised reports {@link ScoringFormulas#RECENCY_NEVER_DAYS}.
     * That is now a genuine signal rather than the default it used to be: the column was being
     * overwritten with the current time on every refresh, so this method returned 0 for
     * everything and no skill could ever decay.
     */
    private long daysSincePractice(TopicMastery tm) {
        Instant last = tm.getLastPracticedAt();
        if (last == null) return ScoringFormulas.RECENCY_NEVER_DAYS;
        double days = Duration.between(last, Instant.now()).toSeconds() / 86_400.0;
        return Math.max(0, Math.round(days));
    }

    private int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    /** Kept so callers can filter out null rating changes the same way this class does. */
    static List<Integer> ratingChanges(List<ContestSummary> contests) {
        return contests.stream()
            .map(ContestSummary::getRatingChange)
            .filter(Objects::nonNull)
            .toList();
    }
}
