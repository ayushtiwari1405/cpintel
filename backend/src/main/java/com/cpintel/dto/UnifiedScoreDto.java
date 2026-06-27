package com.cpintel.dto;

import lombok.*;
import java.time.Instant;

@Getter @Builder
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
