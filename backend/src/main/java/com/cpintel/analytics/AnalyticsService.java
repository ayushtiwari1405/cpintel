package com.cpintel.analytics;

import com.cpintel.dto.analytics.AnalyticsDto;
import com.cpintel.entity.ContestSummary;
import com.cpintel.entity.PlatformAccount;
import com.cpintel.entity.TopicMastery;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.ContestSummaryRepository;
import com.cpintel.repository.jpa.PlatformAccountRepository;
import com.cpintel.repository.jpa.TopicMasteryRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.roadmap.RoadmapTaxonomy;
import com.cpintel.roadmap.SkillAttribution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsService {

    private final UserRepository userRepository;
    private final TopicMasteryRepository topicMasteryRepository;
    private final ContestSummaryRepository contestSummaryRepository;
    private final PlatformAccountRepository platformAccountRepository;
    private final PracticeHistory practiceHistory;
    private final AnalyticsEngine analyticsEngine;
    private final UnifiedRatingService unifiedRatingService;
    private final ContestAnalysisService contestAnalysisService;
    private final RecommendationEngine recommendationEngine;

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("UTC"));

    /** Days of activity the trends endpoint reports on. */
    private static final int TREND_WINDOW_DAYS = 90;

    /** Days counted as "recent" when reporting how much work went into a topic lately. */
    private static final int RECENT_WINDOW_DAYS = 30;

    @Cacheable(value = "analytics", key = "'overview:' + #userId")
    public AnalyticsDto.Overview getOverview(Long userId) {
        userRepository.findById(userId)
            .orElseThrow(() -> ApiException.notFound("User not found"));

        List<TopicMastery> rollups =
            topicMasteryRepository.findByUserUserIdAndScope(userId, TopicMastery.Scope.TOPIC);
        List<PlatformAccount> platforms = platformAccountRepository.findByUserUserId(userId);
        List<ContestSummary> contests =
            contestSummaryRepository.findByUserUserIdOrderByContestDateDesc(userId);

        // Contests are grouped once here rather than re-queried per platform. The previous
        // version issued two extra queries for every linked account - one for accuracy and one
        // for the average rating change - while already holding the whole list in memory.
        Map<String, List<ContestSummary>> contestsByPlatform = contests.stream()
            .collect(Collectors.groupingBy(ContestSummary::getPlatform));

        List<AnalyticsDto.TopicSummary> topicSummaries = rollups.stream()
            .map(this::toTopicSummary)
            .sorted(Comparator.comparingDouble(
                (AnalyticsDto.TopicSummary t) -> t.getMasteryScore() == null ? 0 : t.getMasteryScore())
                .reversed())
            .collect(Collectors.toList());

        List<AnalyticsDto.PlatformStats> platformStats = platforms.stream()
            .map(pa -> {
                List<ContestSummary> own =
                    contestsByPlatform.getOrDefault(pa.getPlatform(), List.of());
                return AnalyticsDto.PlatformStats.builder()
                    .platform(pa.getPlatform())
                    .currentRating(pa.getCurrentRating())
                    .maxRating(pa.getMaxRating())
                    .totalContests(own.size())
                    .avgRatingChange(averageRatingChange(own))
                    .accuracy(analyticsEngine.accuracyOf(own))
                    .build();
            })
            .collect(Collectors.toList());

        List<SkillAttribution.Practised> history = practiceHistory.forUser(userId);
        int totalSolved = (int) history.stream().filter(SkillAttribution.Practised::solved).count();

        return AnalyticsDto.Overview.builder()
            .unifiedScore(unifiedRatingService.computeBreakdown(userId).unifiedScore())
            .overallConsistency(analyticsEngine.consistencyOf(contests))
            .totalSolved(totalSolved)
            .totalContests(contests.size())
            .currentStreak(practiceHistory.currentStreak(history))
            .topicSummaries(topicSummaries)
            .platformStats(platformStats)
            .build();
    }

    @Cacheable(value = "analytics", key = "'contests:' + #userId")
    public AnalyticsDto.ContestAnalytics getContestAnalytics(Long userId) {
        List<ContestSummary> contests =
            contestSummaryRepository.findByUserUserIdOrderByContestDateDesc(userId);

        if (contests.isEmpty()) {
            return AnalyticsDto.ContestAnalytics.builder()
                .ratingHistory(List.of())
                .insights(List.of("No contest history yet. Link a platform and sync to get started."))
                .build();
        }

        List<AnalyticsDto.ContestPoint> history = contests.stream()
            .filter(c -> c.getContestDate() != null)
            .sorted(Comparator.comparing(ContestSummary::getContestDate))
            .map(c -> AnalyticsDto.ContestPoint.builder()
                .contestName(c.getContestName())
                .platform(c.getPlatform())
                .ratingAfter(c.getRatingAfter())
                .ratingChange(c.getRatingChange())
                .date(DATE_FMT.format(c.getContestDate()))
                .build())
            .collect(Collectors.toList());

        double avgChange = contests.stream()
            .filter(c -> c.getRatingChange() != null)
            .mapToInt(ContestSummary::getRatingChange)
            .average().orElse(0);

        double avgWrong = contests.stream()
            .filter(c -> c.getWrongSubmissions() != null)
            .mapToInt(ContestSummary::getWrongSubmissions)
            .average().orElse(0);

        double avgFirst = contests.stream()
            .filter(c -> c.getFirstSolveMins() != null)
            .mapToInt(ContestSummary::getFirstSolveMins)
            .average().orElse(0);

        int peak = contests.stream()
            .filter(c -> c.getRatingAfter() != null)
            .mapToInt(ContestSummary::getRatingAfter)
            .max().orElse(0);

        return AnalyticsDto.ContestAnalytics.builder()
            .ratingHistory(history)
            .avgRatingChange(ScoringFormulas.round2(avgChange))
            .peakRating(peak)
            .avgWrongSubmissions(ScoringFormulas.round2(avgWrong))
            .avgFirstSolveMins(ScoringFormulas.round2(avgFirst))
            .consistencyScore(analyticsEngine.consistencyOf(contests))
            .insights(generateContestInsights(contests, avgWrong, avgFirst))
            .build();
    }

    /** The coarse roll-ups, for the radar and the mastery table. */
    @Cacheable(value = "analytics", key = "'topics:' + #userId")
    public List<AnalyticsDto.TopicSummary> getTopicAnalytics(Long userId) {
        return topicMasteryRepository
            .findByUserUserIdAndScope(userId, TopicMastery.Scope.TOPIC).stream()
            .map(this::toTopicSummary)
            .sorted(Comparator.comparingDouble(
                (AnalyticsDto.TopicSummary t) -> t.getMasteryScore() == null ? 0 : t.getMasteryScore())
                .reversed())
            .collect(Collectors.toList());
    }

    /** Every skill-tree node the user has evidence for, strongest first. */
    @Cacheable(value = "analytics", key = "'nodes:' + #userId")
    public List<AnalyticsDto.TopicSummary> getNodeAnalytics(Long userId) {
        return topicMasteryRepository
            .findByUserUserIdAndScope(userId, TopicMastery.Scope.NODE).stream()
            .map(this::toTopicSummary)
            .sorted(Comparator.comparingDouble(
                (AnalyticsDto.TopicSummary t) -> t.getMasteryScore() == null ? 0 : t.getMasteryScore())
                .reversed())
            .collect(Collectors.toList());
    }

    /**
     * Daily solve counts and per-topic standing.
     *
     * <p>This endpoint used to return two empty lists and a tag histogram. {@code dailyActivity}
     * and {@code topicProgress} were both hard-coded {@code List.of()}, so anything that charted
     * them drew nothing and could not tell that apart from a user with no history.
     *
     * <p>{@code topicProgress} reports where each topic stands now rather than a series over
     * time: no mastery history is stored, and inventing a curve from a single sample would be
     * worse than reporting the sample honestly.
     */
    @Cacheable(value = "analytics", key = "'trends:' + #userId")
    public AnalyticsDto.TrendData getTrends(Long userId) {
        List<SkillAttribution.Practised> history = practiceHistory.forUser(userId);

        List<AnalyticsDto.ActivityPoint> daily = new ArrayList<>();
        Map<LocalDate, Integer> byDay = practiceHistory.solvesByDay(history, TREND_WINDOW_DAYS);
        Map<LocalDate, Set<String>> topicsByDay = topicsTouchedByDay(history);
        for (Map.Entry<LocalDate, Integer> e : byDay.entrySet()) {
            daily.add(AnalyticsDto.ActivityPoint.builder()
                .date(e.getKey().toString())
                .problemsSolved(e.getValue())
                .topicsPracticed(topicsByDay.getOrDefault(e.getKey(), Set.of()).size())
                .build());
        }

        Map<String, Integer> recentByTopic = recentSolvesByTopic(history);
        Map<String, Long> nodesPerTopic = RoadmapTaxonomy.NODES.stream()
            .collect(Collectors.groupingBy(RoadmapTaxonomy.NodeDef::rollupTopic,
                Collectors.counting()));
        Map<String, Long> touchedPerTopic = topicMasteryRepository
            .findByUserUserIdAndScope(userId, TopicMastery.Scope.NODE).stream()
            .filter(tm -> tm.getParentTopic() != null)
            .collect(Collectors.groupingBy(TopicMastery::getParentTopic, Collectors.counting()));

        List<AnalyticsDto.TopicProgress> progress = topicMasteryRepository
            .findByUserUserIdAndScope(userId, TopicMastery.Scope.TOPIC).stream()
            .map(tm -> AnalyticsDto.TopicProgress.builder()
                .topic(tm.getTopic())
                .masteryScore(orZero(tm.getMasteryScore()))
                .retainedScore(orZero(tm.getRevisionScore()))
                .solvedRecently(recentByTopic.getOrDefault(tm.getTopic(), 0))
                .nodesTouched(touchedPerTopic.getOrDefault(tm.getTopic(), 0L).intValue())
                .nodesTotal(nodesPerTopic.getOrDefault(tm.getTopic(), 0L).intValue())
                .build())
            .sorted(Comparator.comparingDouble(
                (AnalyticsDto.TopicProgress t) -> t.getMasteryScore() == null ? 0 : t.getMasteryScore())
                .reversed())
            .collect(Collectors.toList());

        Map<String, Integer> tagCounts = new HashMap<>();
        for (SkillAttribution.Practised p : history) {
            if (!p.solved()) continue;
            for (String tag : p.cfTags()) tagCounts.merge(tag, 1, Integer::sum);
        }

        return AnalyticsDto.TrendData.builder()
            .dailyActivity(daily)
            .topicProgress(progress)
            .submissionsByTag(tagCounts)
            .build();
    }

    @Transactional
    @CacheEvict(value = "analytics", allEntries = true)
    public void triggerAnalyticsRefresh(Long userId) {
        log.info("Triggering analytics refresh for user {}", userId);
        // Step 1: fold the raw submissions into per-skill counts
        updateTopicMasteryFromSubmissions(userId);
        // Step 2: score the populated rows and regenerate the derived sheets
        analyticsEngine.refreshAllMastery(userId);
        contestAnalysisService.detectBehaviorPatterns(userId);
        recommendationEngine.generateDailySheet(userId);
        recommendationEngine.generateWeeklySheet(userId);
        recommendationEngine.generateRevisionSchedule(userId);
    }

    /**
     * Rebuild every mastery row for a user from their submission history.
     *
     * <p>Writes at two granularities from one pass: one row per skill-tree node the user has
     * evidence for, and one roll-up row per coarse topic for the radar. The roll-ups are
     * recomputed from the history rather than summed from the nodes, because a problem
     * attributed to several nodes in the same topic would otherwise be counted several times.
     */
    @Transactional
    public void updateTopicMasteryFromSubmissions(Long userId) {
        List<SkillAttribution.Practised> history = practiceHistory.forUser(userId);

        Map<String, SkillAttribution.NodeStat> nodeStats = SkillAttribution.attribute(history);
        Map<String, SkillAttribution.NodeStat> topicStats = SkillAttribution.rollUp(history);

        if (nodeStats.isEmpty() && topicStats.isEmpty()) {
            log.warn("No skills attributed for user {} from {} distinct problems",
                userId, history.size());
            return;
        }

        // Confirms the account exists before writing rows keyed to it - the upsert below is
        // native, so it would not raise the foreign key as a readable error on its own.
        if (!userRepository.existsById(userId)) {
            throw ApiException.notFound("User not found");
        }

        List<String> written = new ArrayList<>(nodeStats.size() + topicStats.size());

        for (Map.Entry<String, SkillAttribution.NodeStat> e : nodeStats.entrySet()) {
            RoadmapTaxonomy.NodeDef def = RoadmapTaxonomy.byId(e.getKey());
            if (def == null) continue;
            SkillAttribution.NodeStat stat = e.getValue();
            topicMasteryRepository.upsertCounts(
                userId, def.id(), TopicMastery.Scope.NODE,
                def.rollupTopic(), def.track(),
                stat.solved(), stat.attempted(), stat.lastPractisedAt());
            written.add(def.id());
        }

        for (Map.Entry<String, SkillAttribution.NodeStat> e : topicStats.entrySet()) {
            SkillAttribution.NodeStat stat = e.getValue();
            topicMasteryRepository.upsertCounts(
                userId, e.getKey(), TopicMastery.Scope.TOPIC,
                null, null,
                stat.solved(), stat.attempted(), stat.lastPractisedAt());
            written.add(e.getKey());
        }

        // Rows for skills the user no longer has evidence for - a renamed node, a retired one,
        // or a platform they unlinked - would otherwise sit frozen at their last score and keep
        // appearing in the radar and the revision queue forever.
        if (!written.isEmpty()) {
            topicMasteryRepository.deleteStale(userId, written);
        }

        log.info("Mastery rebuilt for user {}: {} nodes, {} topics, from {} distinct problems",
            userId, nodeStats.size(), topicStats.size(), history.size());
    }

    // -- internals ---------------------------------------------------------

    private Double averageRatingChange(List<ContestSummary> contests) {
        return contests.stream()
            .filter(c -> c.getRatingChange() != null)
            .mapToInt(ContestSummary::getRatingChange)
            .average()
            .stream().boxed().findFirst()
            .map(ScoringFormulas::round2)
            .orElse(null);
    }

    /** Distinct roll-up topics touched on each UTC day inside the trend window. */
    private Map<LocalDate, Set<String>> topicsTouchedByDay(List<SkillAttribution.Practised> history) {
        Map<LocalDate, Set<String>> byDay = new LinkedHashMap<>();
        for (SkillAttribution.Practised p : history) {
            if (!p.solved() || p.lastAttemptAt() == null) continue;
            LocalDate day = LocalDate.ofInstant(p.lastAttemptAt(), ZoneId.of("UTC"));
            Set<String> topics = byDay.computeIfAbsent(day, k -> new java.util.LinkedHashSet<>());
            for (String nodeId : SkillAttribution.nodesFor(p.rating(), p.cfTags())) {
                RoadmapTaxonomy.NodeDef def = RoadmapTaxonomy.byId(nodeId);
                if (def != null) topics.add(def.rollupTopic());
            }
        }
        return byDay;
    }

    /** Problems solved per roll-up topic in the recent window. */
    private Map<String, Integer> recentSolvesByTopic(List<SkillAttribution.Practised> history) {
        Instant cutoff = Instant.now().minusSeconds(RECENT_WINDOW_DAYS * 86_400L);
        Map<String, Integer> counts = new HashMap<>();
        for (SkillAttribution.Practised p : history) {
            if (!p.solved() || p.lastAttemptAt() == null || p.lastAttemptAt().isBefore(cutoff)) {
                continue;
            }
            Set<String> topics = new java.util.LinkedHashSet<>();
            for (String nodeId : SkillAttribution.nodesFor(p.rating(), p.cfTags())) {
                RoadmapTaxonomy.NodeDef def = RoadmapTaxonomy.byId(nodeId);
                if (def != null) topics.add(def.rollupTopic());
            }
            for (String topic : topics) counts.merge(topic, 1, Integer::sum);
        }
        return counts;
    }

    private AnalyticsDto.TopicSummary toTopicSummary(TopicMastery tm) {
        return AnalyticsDto.TopicSummary.builder()
            .topic(tm.getTopic())
            .displayName(displayNameOf(tm))
            .scope(tm.getScope())
            .parentTopic(tm.getParentTopic())
            .track(tm.getTrack())
            .masteryScore(tm.getMasteryScore())
            .confidenceScore(tm.getConfidenceScore())
            .decayScore(tm.getDecayScore())
            .revisionScore(tm.getRevisionScore())
            .problemsSolved(tm.getProblemsSolved())
            .problemsAttempted(tm.getProblemsAttempted())
            .lastPracticedAt(tm.getLastPracticedAt())
            .masteryBand(TopicMastery.MasteryBand.from(orZero(tm.getMasteryScore())).name())
            .build();
    }

    /** A NODE row's topic column holds an id; the tree knows what to call it. */
    private String displayNameOf(TopicMastery tm) {
        if (!tm.isNode()) return tm.getTopic();
        RoadmapTaxonomy.NodeDef def = RoadmapTaxonomy.byId(tm.getTopic());
        return def == null ? tm.getTopic() : def.displayName();
    }

    private double orZero(Double value) {
        return value == null ? 0.0 : value;
    }

    private List<String> generateContestInsights(
        List<ContestSummary> contests, double avgWrong, double avgFirst) {
        List<String> insights = new ArrayList<>();
        if (avgWrong > 3) {
            insights.add("You average " + String.format("%.1f", avgWrong)
                + " wrong submissions per contest. Focus on testing edge cases before submitting.");
        }
        if (avgFirst > 20) {
            insights.add("Your average first solve takes " + String.format("%.0f", avgFirst)
                + " minutes. Practice reading speed on easier problems.");
        }
        long positive = contests.stream()
            .filter(c -> c.getRatingChange() != null && c.getRatingChange() > 0).count();
        int pct = contests.isEmpty() ? 0 : (int) (positive * 100 / contests.size());
        insights.add("You gain rating in " + pct + "% of contests.");
        if (insights.size() == 1) {
            insights.add("Keep competing consistently to unlock detailed insights.");
        }
        return insights;
    }
}
