package com.cpintel.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;

/**
 * One question in a classroom's question bank, written in CPIntel rather than on the judge.
 *
 * <p>Its tests are {@link BankQuestionTest} rows, and their bytes are kept apart again, so
 * nothing here grows with the test data. {@code version} counts edits; a save names the version
 * it started from and is refused if someone else saved in between.
 */
@Entity
@Table(name = "bank_questions")
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankQuestion {

    public enum Checker { EXACT, IGNORE_CASE, FLOAT }

    public enum Status { DRAFT, READY }

    /** Whether candidates read the rendered Markdown or the setter's own PDF. */
    public enum StatementKind { MARKDOWN, PDF }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "question_id")
    private Long questionId;

    @Column(name = "classroom_id", nullable = false)
    private Long classroomId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "statement", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String statement = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "statement_kind", nullable = false, length = 16)
    @Builder.Default
    private StatementKind statementKind = StatementKind.MARKDOWN;

    @Column(name = "time_limit_ms", nullable = false)
    @Builder.Default
    private Integer timeLimitMs = 1000;

    @Column(name = "memory_limit_mb", nullable = false)
    @Builder.Default
    private Integer memoryLimitMb = 256;

    @Enumerated(EnumType.STRING)
    @Column(name = "checker", nullable = false, length = 16)
    @Builder.Default
    private Checker checker = Checker.EXACT;

    @Column(name = "float_tolerance")
    private Double floatTolerance;

    @Column(name = "model_language", length = 16)
    private String modelLanguage;

    @Column(name = "model_source", columnDefinition = "TEXT")
    private String modelSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private Status status = Status.DRAFT;

    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 0;

    @Column(name = "archived", nullable = false)
    @Builder.Default
    private Boolean archived = false;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    /** Records an edit: one more version, back to draft until it is checked again. */
    public void touched(Long userId) {
        version = version + 1;
        status = Status.DRAFT;
        updatedBy = userId;
        updatedAt = Instant.now();
    }
}
