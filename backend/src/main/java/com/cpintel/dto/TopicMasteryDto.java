package com.cpintel.dto;

import lombok.*;
import java.time.Instant;

@Getter @Builder
public class TopicMasteryDto {
    private Long masteryId;
    private String topic;
    private Double masteryScore;
    private Double confidenceScore;
    private Double revisionScore;
    private Double decayScore;
    private Integer problemsSolved;
    private Integer problemsAttempted;
    private Instant lastPracticedAt;
    private String masteryBand;
}
