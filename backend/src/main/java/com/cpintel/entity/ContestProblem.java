package com.cpintel.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One problem of an event, as CPIntel needs it: where it comes in the order, and what it is
 * worth.
 *
 * <p><b>The statement is not here, and that is the point.</b> The judge owns the statement, the
 * test data and the verdict; copying them into CPIntel would create a second source of truth
 * for the one thing the judge is genuinely authoritative about, and the copy would be wrong
 * from the moment somebody fixed a test case. What CPIntel does own is the arrangement — which
 * problems are in this event, in what order, and how many points or marks each carries — and
 * that is what this row is.
 */
@Entity
@Table(name = "contest_problems")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContestProblem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "problem_id")
    private Long problemId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contest_id", nullable = false)
    private GroupContest contest;

    /** A, B, C… — what the contestant sees, and how a submission names the problem. */
    @Column(name = "label", nullable = false, length = 16)
    private String label;

    @Column(name = "title", length = 200)
    private String title;

    /** The judge's own problem id, when it differs from the label. */
    @Column(name = "external_id", length = 100)
    private String externalId;

    @Column(name = "ordering", nullable = false)
    @Builder.Default
    private Integer ordering = 0;

    /** Points in a contest, marks in an examination. The same number either way. */
    @Column(name = "points", precision = 10, scale = 2)
    private BigDecimal points;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
