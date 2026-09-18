package com.cpintel.dto;

import lombok.*;
import lombok.extern.jackson.Jacksonized;
import java.time.Instant;

@Getter @Builder @Jacksonized
public class UnifiedScoreDto {
    private Double cfScore;
    private Double lcScore;
    private Double ccScore;
    private Double unifiedScore;
    private Double cfWeight;
    private Double lcWeight;
    private Double ccWeight;
    private Instant computedAt;
}
