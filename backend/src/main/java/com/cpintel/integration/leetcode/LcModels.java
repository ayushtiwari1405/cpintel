package com.cpintel.integration.leetcode;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.List;

public class LcModels {

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProfileResponse {
        private ProfileData data;
        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        public static class ProfileData {
            private UserProfile matchedUser;
        }
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UserProfile {
        private String username;
        private SubmitStats submitStats;
        private Profile profile;
        private UserCalendar userCalendar;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SubmitStats {
        private List<DifficultyCount> acSubmissionNum;
        private List<DifficultyCount> totalSubmissionNum;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DifficultyCount {
        private String difficulty;
        private Integer count;
        private Integer submissions;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Profile {
        private Integer ranking;
        private Integer reputation;
        private Double starRating;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UserCalendar {
        private Integer streak;
        private Integer totalActiveDays;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SubmissionListResponse {
        private RecentSubmissions data;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RecentSubmissions {
        private List<Submission> recentAcSubmissionList;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Submission {
        private String id;
        private String title;
        private String titleSlug;
        private String timestamp;
        private String lang;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContestRankingResponse {
        private ContestData data;
        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        public static class ContestData {
            private ContestRanking userContestRanking;
        }
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContestRanking {
        private Integer attendedContestsCount;
        private Double rating;
        private Integer globalRanking;
    }
}
