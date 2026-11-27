package com.cpintel.dto;

import jakarta.validation.constraints.*;
import lombok.*;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;
import java.util.List;

public class UserDto {

    @Getter @Builder @Jacksonized
    public static class Profile {
        private Long userId;
        private String username;
        private String email;
        private String fullName;
        private String avatarUrl;
        private String country;
        private String institution;
        private String role;
        private Boolean isVerified;
        private Instant createdAt;
        /**
         * When the owner last set this password themselves, or null if they never have.
         *
         * Returned so their own profile can tell them they are still using the password they
         * were given — which, until this is set, somebody else also knows.
         */
        private Instant passwordChangedAt;
        private List<PlatformDto.Summary> platforms;
        private UnifiedScoreDto unifiedScore;
    }

    @Getter @Setter
    public static class UpdateRequest {
        @Size(max = 100, message = "Full name can be at most 100 characters")
        private String fullName;

        @Size(max = 100, message = "Country can be at most 100 characters")
        private String country;

        @Size(max = 200, message = "Institution can be at most 200 characters")
        private String institution;

        /** Empty to clear it. Only a web address: this is a value a page may one day link to. */
        @Size(max = 500, message = "An avatar address can be at most 500 characters")
        @Pattern(regexp = "(?i)^(https?://\\S+)?$",
            message = "An avatar address has to start with http:// or https://")
        private String avatarUrl;
    }

    @Getter @Builder @Jacksonized
    public static class DashboardData {
        private Profile user;
        private UnifiedScoreDto unifiedScore;
        private List<TopicMasteryDto> topTopics;
        private List<ContestSummaryDto> recentContests;
        private int currentStreak;
    }
}
