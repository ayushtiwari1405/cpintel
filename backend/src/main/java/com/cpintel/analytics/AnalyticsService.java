package com.cpintel.analytics;

import com.cpintel.dto.analytics.AnalyticsDto;
import com.cpintel.entity.ContestSummary;
import com.cpintel.entity.PlatformAccount;
import com.cpintel.entity.TopicMastery;
import com.cpintel.entity.User;
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
import org.springframework.cache.annotation.Caching;
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
    private final SkillRatingService skillRatings;
    private final com.cpintel.service.RoadmapService roadmapService;

    /** Through the proxy, so the per-user cache eviction on the refresh actually happens. */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private AnalyticsService self;

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("UTC"));

    /** Days of activity the trends endpoint reports on. */
    private static final int TREND_WINDOW_DAYS = 90;

    /** Days counted as "recent" when reporting how much work went into a topic lately. */
    private static final int RECENT_WINDOW_DAYS = 30;

    /**
     * Strongest skill first: by rating where there is one, otherwise by how much was solved —
     * the order coverage is read in.
     */
    private static final Comparator<AnalyticsDto.TopicSummary> STRONGEST_FIRST =
        Comparator.comparing((AnalyticsDto.TopicSummary t) -> t.getSkillRating() == null ? 1 : 0)
            .thenComparing(t -> -(t.getSkillRating() == null ? 0 : t.getSkillRating()))
            .thenComparing(t -> -(t.getProblemsSolved() == null ? 0 : t.getProblemsSolved()));

    /** What this user's skill ratings are based on, and what they could be based on. */
    public AnalyticsDto.SkillBasis getSkillBasis(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> ApiException.notFound("User not found"));
        return toDto(skillRatings.summary(user));
    }

    /**
     * Chooses what skill ratings are based on, and rebuilds them on it.
     *
     * @param basis AUTO, CODEFORCES or GAUNTLET
     */
    @Transactional
    public AnalyticsDto.SkillBasis setSkillBasis(Long userId, String basis) {
        String chosen = basis == null ? "" : basis.trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of(SkillRatingService.AUTO, SkillRatingService.CODEFORCES,
                SkillRatingService.GAUNTLET).contains(chosen)) {
            throw ApiException.badRequest("Choose AUTO, CODEFORCES or GAUNTLET.");
        }
        User user = userRepository.findById(userId)
            .orElseThrow(() -> ApiException.notFound("User not found"));
        user.setSkillBasis(chosen);
        userRepository.save(user);
        self.triggerAnalyticsRefresh(userId);
        return toDto(skillRatings.summary(user));
    }

    private static AnalyticsDto.SkillBasis toDto(SkillRatingService.Summary s) {
        return AnalyticsDto.SkillBasis.builder()
            .basis(s.basis()).requested(s.requested()).rating(s.rating())
            .codeforcesAvailable(s.codeforcesAvailable()).gauntletAvailable(s.gauntletAvailable())
            .build();
    }

    @Cacheable(value = "analytics", key = "'overview:' + #userId")
    public AnalyticsDto.Overview getOverview(Long userId) {
        User user = userRepository.findById(userId)
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
            .sorted(STRONGEST_FIRST)
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
            .skillBasis(toDto(skillRatings.summary(user)))
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
            .sorted(STRONGEST_FIRST)
            .collect(Collectors.toList());
    }

    /**
     * Every skill-tree node the user has evidence for, strongest first. With a rating basis
     * every node has a row (the roadmap needs them all), so the ones nothing has touched —
     * no practice and no rated-round problem — are left out here.
     */
    @Cacheable(value = "analytics", key = "'nodes:' + #userId")
    public List<AnalyticsDto.TopicSummary> getNodeAnalytics(Long userId) {
        return topicMasteryRepository
            .findByUserUserIdAndScope(userId, TopicMastery.Scope.NODE).stream()
            .filter(tm -> (tm.getProblemsAttempted() != null && tm.getProblemsAttempted() > 0)
                || (tm.getSkillProblems() != null && tm.getSkillProblems() > 0))
            .map(this::toTopicSummary)
            .sorted(STRONGEST_FIRST)
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

    /**
     * Rebuilds one user's analytics.
     *
     * <p>Evicts that user's cached entries and nobody else's. It used to clear the whole
     * analytics cache, and the dashboard asked for a refresh on every visit, so one person
     * opening their dashboard emptied the cache for everyone and it was effectively never warm.
     */
    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "analytics", key = "'overview:' + #userId"),
        @CacheEvict(value = "analytics", key = "'contests:' + #userId"),
        @CacheEvict(value = "analytics", key = "'topics:' + #userId"),
        @CacheEvict(value = "analytics", key = "'nodes:' + #userId"),
        @CacheEvict(value = "analytics", key = "'trends:' + #userId"),
        @CacheEvict(value = "recommendations", key = "'daily:' + #userId"),
        @CacheEvict(value = "recommendations", key = "'weekly:' + #userId"),
    })
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
        // The roadmap's statuses are read from the ratings just written.
        roadmapService.regenerateRoadmap(userId);
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

        // Confirms the account exists before writing rows keyed to it - the upsert below is
        // native, so it would not raise the foreign key as a readable error on its own.
        User user = userRepository.findById(userId)
            .orElseThrow(() -> ApiException.notFound("User not found"));
        SkillRatingService.Profile skills = skillRatings.profile(user);

        if (nodeStats.isEmpty() && topicStats.isEmpty() && skills.basis() == null) {
            log.warn("No skills attributed for user {} from {} distinct problems",
                userId, history.size());
            return;
        }

        List<String> written = new ArrayList<>(RoadmapTaxonomy.NODES.size() + topicStats.size());

        // With a rating to base them on, every node gets a row: one never practised still has a
        // rating (the person's own, until evidence moves it), and the roadmap reads it. Without
        // one, only what was practised is written, which is what coverage shows.
        Set<String> nodeIds = new java.util.LinkedHashSet<>(nodeStats.keySet());
        nodeIds.addAll(skills.nodes().keySet());
        for (String nodeId : nodeIds) {
            RoadmapTaxonomy.NodeDef def = RoadmapTaxonomy.byId(nodeId);
            if (def == null) continue;
            write(userId, def.id(), TopicMastery.Scope.NODE, def.rollupTopic(), def.track(),
                nodeStats.get(nodeId), skills.nodes().get(nodeId), skills.basis());
            written.add(def.id());
        }

        Set<String> topicIds = new java.util.LinkedHashSet<>(topicStats.keySet());
        topicIds.addAll(skills.topics().keySet());
        for (String topic : topicIds) {
            write(userId, topic, TopicMastery.Scope.TOPIC, null, null,
                topicStats.get(topic), skills.topics().get(topic), skills.basis());
            written.add(topic);
        }

        // Rows for skills the user no longer has evidence for - a renamed node, a retired one,
        // or a platform they unlinked - would otherwise sit frozen at their last score and keep
        // appearing in the radar and the revision queue forever.
        if (!written.isEmpty()) {
            topicMasteryRepository.deleteStale(userId, written);
        }

        log.info("Mastery rebuilt for user {}: {} nodes, {} topics, from {} distinct problems; "
            + "skill ratings from {}", userId, nodeStats.size(), topicStats.size(), history.size(),
            skills.basis() == null ? "nothing (coverage only)" : skills.basis());
    }

    private void write(Long userId, String topic, String scope, String parentTopic, String track,
                       SkillAttribution.NodeStat stat, SkillRatingService.Skill skill,
                       String basis) {
        topicMasteryRepository.upsertCounts(
            userId, topic, scope, parentTopic, track,
            stat == null ? 0 : stat.solved(), stat == null ? 0 : stat.attempted(),
            stat == null ? null : stat.lastPractisedAt(),
            stat == null ? null : stat.maxSolvedRating(),
            skill == null ? null : ScoringFormulas.round2(skill.rating()),
            skill == null ? null : ScoringFormulas.round2(skill.sd()),
            skill == null ? null : skill.problems(),
            skill == null ? null : basis);
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
            .skillRating(tm.getSkillRating())
            .skillSd(tm.getSkillSd())
            .skillProblems(tm.getSkillProblems())
            .maxSolvedRating(tm.getMaxSolvedRating())
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
