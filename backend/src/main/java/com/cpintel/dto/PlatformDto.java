package com.cpintel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.Instant;

public class PlatformDto {

    @Getter @Setter
    public static class LinkRequest {
        @NotBlank(message = "Handle is required")
        @Size(min = 2, max = 100)
        private String handle;
    }

    @Getter @Builder
    public static class Summary {
        private Long accountId;
        private String platform;
        private String handle;
        private Integer currentRating;
        private Integer maxRating;
        private Instant lastSyncedAt;
        private String syncStatus;
    }

    @Getter @Builder
    public static class SyncResponse {
        private Long jobId;
        private String platform;
        private String status;
        private String message;
    }
}

