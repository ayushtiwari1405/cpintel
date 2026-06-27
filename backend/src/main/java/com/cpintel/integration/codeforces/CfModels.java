package com.cpintel.integration.codeforces;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

public class CfModels {

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class User {
        private String handle;
        private String email;
        private String firstName;
        private String lastName;
        private String country;
        private String organization;
        private Integer rating;
        private Integer maxRating;
        private String rank;
        private String maxRank;
        private Long lastOnlineTimeSeconds;
        private Long registrationTimeSeconds;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RatingChange {
        private Integer contestId;
        private String contestName;
        private String handle;
        private Integer rank;
        private Long ratingUpdateTimeSeconds;
        private Integer oldRating;
        private Integer newRating;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Submission {
        private Long id;
        private Long contestId;
        private Long creationTimeSeconds;
        private Problem problem;
        private String verdict;
        private String programmingLanguage;
        private Integer timeConsumedMillis;
        private Long memoryConsumedBytes;
        private Author author;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Problem {
            private Integer contestId;
            private String index;
            private String name;
            private Integer rating;
            private List<String> tags;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Author {
            private String participantType;
        }
    }
}
