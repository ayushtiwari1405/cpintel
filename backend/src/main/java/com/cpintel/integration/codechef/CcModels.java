package com.cpintel.integration.codechef;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;
import java.util.Map;

public class CcModels {

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UserProfile {
        private String username;
        private String name;
        private String country;
        private String institution;

        @JsonProperty("currentRating")
        private Integer currentRating;

        @JsonProperty("highestRating")
        private Integer highestRating;

        @JsonProperty("globalRank")
        private Integer globalRank;

        @JsonProperty("countryRank")
        private Integer countryRank;

        @JsonProperty("ratingData")
        private List<RatingEntry> ratingData;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RatingEntry {
        private String code;
        private String name;
        private String reason;
        private Integer rating;
        private String end_date;
    }
}
