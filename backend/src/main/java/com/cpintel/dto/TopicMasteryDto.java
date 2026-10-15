package com.cpintel.dto;

import lombok.*;
import lombok.extern.jackson.Jacksonized;
import java.time.Instant;

@Getter @Builder @Jacksonized
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
    /** Rating on this topic in Codeforces points; null when coverage is shown instead. */
    private Double skillRating;
    private Double skillSd;
    private Integer skillProblems;
    private Integer maxSolvedRating;
}
