package com.cpintel.dto;

import lombok.*;
import lombok.extern.jackson.Jacksonized;
import java.time.Instant;

@Getter @Builder @Jacksonized
public class UnifiedScoreDto {
    private Double cfScore;
    private Double unifiedScore;
    private Instant computedAt;
}
