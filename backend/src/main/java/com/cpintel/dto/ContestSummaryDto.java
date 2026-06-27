package com.cpintel.dto;

import lombok.*;
import java.time.Instant;

@Getter @Builder
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
