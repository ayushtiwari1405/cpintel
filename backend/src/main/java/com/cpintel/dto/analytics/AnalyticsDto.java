package com.cpintel.dto.analytics;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class AnalyticsDto {

    @Getter @Builder @Jacksonized
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
        private SkillBasis skillBasis;
    }

    /**
     * One scored skill. Serves both granularities: a coarse roll-up topic, and one skill-tree
     * node. {@code scope} says which, and for a node {@code topic} is its stable id while
     * {@code displayName} is what to render.
     */
    @Getter @Builder @Jacksonized
    public static class TopicSummary {
        private String topic;
        private String displayName;
        /** NODE or TOPIC. */
        private String scope;
        /** For a node, the roll-up topic it feeds. */
        private String parentTopic;
        /** For a node, the tree track it sits in. */
        private String track;
        private Double masteryScore;
        private Double confidenceScore;
        private Double decayScore;
        /** What survives the decay - the number the revision queue works against. */
        private Double revisionScore;
        private Integer problemsSolved;
        private Integer problemsAttempted;
        private Instant lastPracticedAt;
        private String masteryBand;
        /** Rating on this skill in Codeforces points; null when there is no basis to rate from. */
        private Double skillRating;
        /** One standard deviation of {@code skillRating}. */
        private Double skillSd;
        /** Rated-round problems behind {@code skillRating}; 0 means it is the starting rating. */
        private Integer skillProblems;
        /** Rating of the hardest problem solved on this skill. */
        private Integer maxSolvedRating;
    }

    /** What skill ratings are based on, for the analytics header and the settings control. */
    @Getter @Builder @Jacksonized
    public static class SkillBasis {
        /** CODEFORCES or GAUNTLET; null when ratings cannot be given and coverage is shown. */
        private String basis;
        /** What the person chose: AUTO, CODEFORCES or GAUNTLET. */
        private String requested;
        /** The rating skills start from on that basis. */
        private Integer rating;
        private boolean codeforcesAvailable;
        private boolean gauntletAvailable;
    }

    @Getter @Builder @Jacksonized
    public static class PlatformStats {
        private String platform;
        private Integer currentRating;
        private Integer maxRating;
        private Integer totalContests;
        private Double avgRatingChange;
        private Double accuracy;
    }

    @Getter @Builder @Jacksonized
    public static class ContestAnalytics {
        private List<ContestPoint> ratingHistory;
        private Double avgRatingChange;
        private Integer peakRating;
        private Double avgWrongSubmissions;
        private Double avgFirstSolveMins;
        private Double consistencyScore;
        private List<String> insights;
    }

    @Getter @Builder @Jacksonized
    public static class ContestPoint {
        private String contestName;
        private String platform;
        private Integer ratingAfter;
        private Integer ratingChange;
        private String date;
    }

    @Getter @Builder @Jacksonized
    public static class TrendData {
        private List<ActivityPoint> dailyActivity;
        private List<TopicProgress> topicProgress;
        private Map<String, Integer> submissionsByTag;
    }

    @Getter @Builder @Jacksonized
    public static class ActivityPoint {
        private String date;
        private Integer problemsSolved;
        private Integer topicsPracticed;
    }

    /**
     * Where one topic stands right now.
     *
     * <p>This used to be {@code masteryOverTime}, a list that was never populated because no
     * mastery history is stored anywhere. Rather than fabricate a curve from a single sample,
     * it reports the sample: what the user reached, what they have retained, how much work went
     * in lately, and how much of the topic's tree they have touched at all.
     */
    @Getter @Builder @Jacksonized
    public static class TopicProgress {
        private String topic;
        private Double masteryScore;
        private Double retainedScore;
        /** Problems solved in this topic over the last 30 days. */
        private Integer solvedRecently;
        /** Skill-tree nodes in this topic the user has any evidence for. */
        private Integer nodesTouched;
        /** Skill-tree nodes in this topic in total. */
        private Integer nodesTotal;
    }
}
