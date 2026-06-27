package com.cpintel.dto.analytics;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

public class AnalyticsDto {

    @Getter @Builder
    public static class Overview {
        private Double unifiedScore;
        private Double cfAccuracy;
        private Double lcAccuracy;
        private Double ccAccuracy;
        private Double overallConsistency;
        private Integer totalSolved;
        private Integer totalContests;
        private Integer currentStreak;
        private List<TopicSummary> topicSummaries;
        private List<PlatformStats> platformStats;
    }

    @Getter @Builder
    public static class TopicSummary {
        private String topic;
        private Double masteryScore;
        private Double confidenceScore;
        private Double decayScore;
        private Integer problemsSolved;
        private String masteryBand;
    }

    @Getter @Builder
    public static class PlatformStats {
        private String platform;
        private Integer currentRating;
        private Integer maxRating;
        private Integer totalContests;
        private Double avgRatingChange;
        private Double accuracy;
    }

    @Getter @Builder
    public static class ContestAnalytics {
        private List<ContestPoint> ratingHistory;
        private Double avgRatingChange;
        private Integer peakRating;
        private Double avgWrongSubmissions;
        private Double avgFirstSolveMins;
        private Double consistencyScore;
        private List<String> insights;
    }

    @Getter @Builder
    public static class ContestPoint {
        private String contestName;
        private String platform;
        private Integer ratingAfter;
        private Integer ratingChange;
        private String date;
    }

    @Getter @Builder
    public static class TrendData {
        private List<ActivityPoint> dailyActivity;
        private List<TopicProgress> topicProgress;
        private Map<String, Integer> submissionsByTag;
    }

    @Getter @Builder
    public static class ActivityPoint {
        private String date;
        private Integer problemsSolved;
        private Integer topicsPracticed;
    }

    @Getter @Builder
    public static class TopicProgress {
        private String topic;
        private List<Double> masteryOverTime;
    }
}
