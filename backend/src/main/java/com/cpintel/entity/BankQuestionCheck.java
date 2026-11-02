package com.cpintel.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;

/** The latest check of a bank question's model solution against its tests. */
@Entity
@Table(name = "bank_question_checks")
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankQuestionCheck {

    public enum Status { QUEUED, RUNNING, PASSED, FAILED, ERROR }

    @Id
    @Column(name = "question_id")
    private Long questionId;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "generate", nullable = false)
    @Builder.Default
    private Boolean generate = false;

    @Column(name = "started_by")
    private Long startedBy;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "summary", length = 1000)
    private String summary;

    @Column(name = "max_time_ms")
    private Integer maxTimeMs;

    /** Per-test results as JSON; see QuestionCheckService.TestResult. */
    @Column(name = "results", columnDefinition = "TEXT")
    private String results;

    public boolean isActive() {
        return status == Status.QUEUED || status == Status.RUNNING;
    }
}
