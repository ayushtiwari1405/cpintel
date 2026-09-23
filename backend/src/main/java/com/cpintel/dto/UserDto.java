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
        @Size(max = 100)
        private String fullName;

        @Size(max = 100)
        private String country;

        @Size(max = 200)
        private String institution;

        @Size(max = 500)
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
