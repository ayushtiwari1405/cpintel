package com.cpintel.analytics;

import com.cpintel.dto.analytics.AnalyticsDto;
import com.cpintel.entity.ContestSummary;
import com.cpintel.entity.PlatformAccount;
import com.cpintel.entity.TopicMastery;
import com.cpintel.entity.User;
import com.cpintel.entity.mongo.CfSubmission;
import com.cpintel.exception.ApiException;
import com.cpintel.integration.TopicTagMapper;
import com.cpintel.repository.jpa.*;
import com.cpintel.repository.mongo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsService {

    private final UserRepository userRepository;
    private final TopicMasteryRepository topicMasteryRepository;
    private final ContestSummaryRepository contestSummaryRepository;
    private final PlatformAccountRepository platformAccountRepository;
    private final CfSubmissionRepository cfSubmissionRepository;
    private final OracleProcedureCaller oracle;

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("UTC"));

    @Cacheable(value = "analytics", key = "'overview:' + #userId")
    public AnalyticsDto.Overview getOverview(Long userId) {
        userRepository.findById(userId)
            .orElseThrow(() -> ApiException.notFound("User not found"));

        List<TopicMastery> masteries = topicMasteryRepository.findByUserUserId(userId);
        List<PlatformAccount> platforms = platformAccountRepository.findByUserUserId(userId);
        List<ContestSummary> contests = contestSummaryRepository.findByUserUserIdOrderByContestDateDesc(userId);

        Double consistency = oracle.calculateConsistency(userId);
        Double unifiedScore = oracle.computeUnifiedScore(userId);

        List<AnalyticsDto.TopicSummary> topicSummaries = masteries.stream()
            .map(this::toTopicSummary)
            .sorted(Comparator.comparingDouble(AnalyticsDto.TopicSummary::getMasteryScore).reversed())
            .collect(Collectors.toList());

        List<AnalyticsDto.PlatformStats> platformStats = platforms.stream()
            .map(pa -> {
                Double acc = oracle.calculateAccuracy(userId, pa.getPlatform());
                long pContests = contests.stream()
                    .filter(c -> c.getPlatform().equals(pa.getPlatform())).count();
                Double avgChange = contestSummaryRepository.getAvgRatingChange(userId, pa.getPlatform());
                return AnalyticsDto.PlatformStats.builder()
                    .platform(pa.getPlatform())
                    .currentRating(pa.getCurrentRating())
                    .maxRating(pa.getMaxRating())
                    .totalContests((int) pContests)
                    .avgRatingChange(avgChange)
                    .accuracy(acc)
                    .build();
            })
            .collect(Collectors.toList());

        int totalSolved = masteries.stream()
            .mapToInt(tm -> tm.getProblemsSolved() == null ? 0 : tm.getProblemsSolved()).sum();

        return AnalyticsDto.Overview.builder()
            .unifiedScore(unifiedScore)
            .overallConsistency(consistency)
            .totalSolved(totalSolved)
            .totalContests(contests.size())
            .currentStreak(0)
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
                .date(c.getContestDate() != null ? DATE_FMT.format(c.getContestDate()) : null)
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

        Double consistency = oracle.calculateConsistency(userId);
        List<String> insights = generateContestInsights(contests, avgWrong, avgFirst);

        return AnalyticsDto.ContestAnalytics.builder()
            .ratingHistory(history)
            .avgRatingChange(Math.round(avgChange * 100.0) / 100.0)
            .peakRating(peak)
            .avgWrongSubmissions(Math.round(avgWrong * 100.0) / 100.0)
            .avgFirstSolveMins(Math.round(avgFirst * 100.0) / 100.0)
            .consistencyScore(consistency)
            .insights(insights)
            .build();
    }

    @Cacheable(value = "analytics", key = "'topics:' + #userId")
    public List<AnalyticsDto.TopicSummary> getTopicAnalytics(Long userId) {
        return topicMasteryRepository.findByUserUserId(userId).stream()
            .map(this::toTopicSummary)
            .sorted(Comparator.comparingDouble(AnalyticsDto.TopicSummary::getMasteryScore).reversed())
            .collect(Collectors.toList());
    }

    @Cacheable(value = "analytics", key = "'trends:' + #userId")
    public AnalyticsDto.TrendData getTrends(Long userId) {
        List<CfSubmission> cfSubs = cfSubmissionRepository.findByUserId(userId);
        Map<String, Integer> tagCounts = new HashMap<>();
        for (CfSubmission sub : cfSubs) {
            if ("OK".equals(sub.getVerdict()) && sub.getTags() != null) {
                sub.getTags().forEach(tag -> tagCounts.merge(tag, 1, Integer::sum));
            }
        }
        return AnalyticsDto.TrendData.builder()
            .dailyActivity(List.of())
            .topicProgress(List.of())
            .submissionsByTag(tagCounts)
            .build();
    }

    @Transactional
    @CacheEvict(value = "analytics", allEntries = true)
    public void triggerAnalyticsRefresh(Long userId) {
        log.info("Triggering analytics refresh for user {}", userId);
        // Step 1: Process raw submissions into topic_mastery rows
        updateTopicMasteryFromSubmissions(userId);
        // Step 2: Run PL/SQL analytics on the populated rows
        oracle.refreshAllMastery(userId);
        oracle.detectBehaviorPatterns(userId);
        oracle.generateDailySheet(userId);
        oracle.generateWeeklySheet(userId);
        oracle.generateRevisionSchedule(userId);
    }

    @Transactional
    public void updateTopicMasteryFromSubmissions(Long userId) {
        List<CfSubmission> subs = cfSubmissionRepository.findByUserId(userId);
        Map<String, int[]> topicStats = new HashMap<>(); // [solved, attempted]

        for (CfSubmission sub : subs) {
            if (sub.getTags() == null) continue;
            boolean accepted = "OK".equals(sub.getVerdict());
            for (String tag : sub.getTags()) {
                // Normalize tag to canonical topic
                String canonical = normalizeTag(tag);
                if (canonical == null) continue;
                topicStats.computeIfAbsent(canonical, k -> new int[]{0, 0});
                topicStats.get(canonical)[1]++; // attempted
                if (accepted) topicStats.get(canonical)[0]++; // solved
            }
        }

        if (topicStats.isEmpty()) {
            log.warn("No topic stats extracted for user {} from {} submissions", userId, subs.size());
            return;
        }

        User user = userRepository.findById(userId)
            .orElseThrow(() -> ApiException.notFound("User not found"));

        log.info("Updating topic mastery for user {} — {} topics from {} submissions",
            userId, topicStats.size(), subs.size());

        for (Map.Entry<String, int[]> e : topicStats.entrySet()) {
            String topic = e.getKey();
            int solved    = e.getValue()[0];
            int attempted = e.getValue()[1];

            TopicMastery tm = topicMasteryRepository
                .findByUserUserIdAndTopic(userId, topic)
                .orElseGet(() -> TopicMastery.builder()
                    .user(user).topic(topic).build());

            tm.setProblemsSolved(solved);
            tm.setProblemsAttempted(attempted);
            tm.setLastPracticedAt(Instant.now());
            topicMasteryRepository.save(tm);
        }

        log.info("Topic mastery updated for user {}: {} topics saved", userId, topicStats.size());
    }

    private String normalizeTag(String tag) {
        if (tag == null) return null;
        Map<String, String> tagMap = TopicTagMapper.TAG_MAP_STATIC;
        return tagMap.getOrDefault(tag.toLowerCase(), null);
    }

    private AnalyticsDto.TopicSummary toTopicSummary(TopicMastery tm) {
        return AnalyticsDto.TopicSummary.builder()
            .topic(tm.getTopic())
            .masteryScore(tm.getMasteryScore())
            .confidenceScore(tm.getConfidenceScore())
            .decayScore(tm.getDecayScore())
            .problemsSolved(tm.getProblemsSolved())
            .masteryBand(TopicMastery.MasteryBand.from(
                tm.getMasteryScore() == null ? 0 : tm.getMasteryScore()).name())
            .build();
    }

    private List<String> generateContestInsights(
        List<ContestSummary> contests, double avgWrong, double avgFirst) {
        List<String> insights = new ArrayList<>();
        if (avgWrong > 3)
            insights.add("You average " + String.format("%.1f", avgWrong) +
                " wrong submissions per contest. Focus on testing edge cases before submitting.");
        if (avgFirst > 20)
            insights.add("Your average first solve takes " + String.format("%.0f", avgFirst) +
                " minutes. Practice reading speed on easier problems.");
        long positive = contests.stream()
            .filter(c -> c.getRatingChange() != null && c.getRatingChange() > 0).count();
        int pct = contests.isEmpty() ? 0 : (int)(positive * 100 / contests.size());
        insights.add("You gain rating in " + pct + "% of contests.");
        if (insights.size() == 1)
            insights.add("Keep competing consistently to unlock detailed insights.");
        return insights;
    }
}
