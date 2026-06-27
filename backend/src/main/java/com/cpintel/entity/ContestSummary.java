package com.cpintel.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "contest_summaries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContestSummary extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "contest_id")
    private Long contestId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "platform", nullable = false, length = 20)
    private String platform;

    @Column(name = "contest_name", length = 200)
    private String contestName;

    @Column(name = "contest_cf_id", length = 100)
    private String contestCfId;

    @Column(name = "rank")
    private Integer rank;

    @Column(name = "rating_before")
    private Integer ratingBefore;

    @Column(name = "rating_after")
    private Integer ratingAfter;

    @Column(name = "rating_change")
    private Integer ratingChange;

    @Column(name = "problems_solved")
    @Builder.Default
    private Integer problemsSolved = 0;

    @Column(name = "total_problems")
    @Builder.Default
    private Integer totalProblems = 0;

    @Column(name = "first_solve_mins")
    private Integer firstSolveMins;

    @Column(name = "avg_solve_mins")
    private Integer avgSolveMins;

    @Column(name = "wrong_submissions")
    @Builder.Default
    private Integer wrongSubmissions = 0;

    @Column(name = "penalty_mins")
    @Builder.Default
    private Integer penaltyMins = 0;

    @Column(name = "contest_date")
    private Instant contestDate;
}
