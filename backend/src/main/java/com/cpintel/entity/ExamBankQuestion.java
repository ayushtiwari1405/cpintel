package com.cpintel.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.Instant;

/** A bank question chosen for an examination, and what of it is on the judge. */
@Entity
@Table(name = "exam_bank_questions")
@IdClass(ExamBankQuestion.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExamBankQuestion {

    @EqualsAndHashCode
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private Long contestId;
        private Long questionId;
    }

    @Id
    @Column(name = "contest_id")
    private Long contestId;

    @Id
    @Column(name = "question_id")
    private Long questionId;

    @Column(name = "ordinal", nullable = false)
    private Integer ordinal;

    @Column(name = "judge_problem_id", length = 100)
    private String judgeProblemId;

    @Column(name = "label", length = 16)
    private String label;

    @Column(name = "published_version")
    private Integer publishedVersion;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by")
    private Long publishedBy;
}
