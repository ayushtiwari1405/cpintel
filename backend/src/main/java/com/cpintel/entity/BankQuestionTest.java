package com.cpintel.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One test of a bank question: where it sits and how big it is. The bytes are a
 * {@link BankQuestionTestData} row, read only when someone downloads or judges the test.
 */
@Entity
@Table(name = "bank_question_tests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankQuestionTest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "test_id")
    private Long testId;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    /** Shown to candidates in the statement and the arena; a secret test is only judged. */
    @Column(name = "sample", nullable = false)
    private Boolean sample;

    /** Position among the question's samples, or among its secret tests, from 1. */
    @Column(name = "ordinal", nullable = false)
    private Integer ordinal;

    /** The file name it was uploaded under, shown in the list and never used as a path. */
    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "input_bytes", nullable = false)
    private Integer inputBytes;

    /** Null while the answer is missing. */
    @Column(name = "answer_bytes")
    private Integer answerBytes;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
