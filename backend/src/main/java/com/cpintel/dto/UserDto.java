package com.cpintel.dto;

import jakarta.validation.constraints.*;
import lombok.*;

import java.time.Instant;
import java.util.List;

public class UserDto {

    @Getter @Builder
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

    @Getter @Builder
    public static class DashboardData {
        private Profile user;
        private UnifiedScoreDto unifiedScore;
        private List<TopicMasteryDto> topTopics;
        private List<ContestSummaryDto> recentContests;
        private int currentStreak;
    }
}
