package com.cpintel.dto;

import lombok.*;
import lombok.extern.jackson.Jacksonized;
import java.time.Instant;

@Getter @Builder @Jacksonized
public class ContestSummaryDto {
    private Long contestId;
    private String platform;
    private String contestName;
    private Integer rank;
    private Integer ratingBefore;
    private Integer ratingAfter;
    private Integer ratingChange;
    private Integer problemsSolved;
    private Integer totalProblems;
    private Integer firstSolveMins;
    private Integer wrongSubmissions;
    private Instant contestDate;
}
