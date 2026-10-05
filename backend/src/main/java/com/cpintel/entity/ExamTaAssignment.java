package com.cpintel.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * What one teaching assistant marks on one examination.
 *
 * <p>A question, a range of students, or both — that question for those students. A null field
 * means all of them. A TA's rows add up: two rows give the union of the two.
 *
 * <p>The range is a span of usernames, both ends included, in natural order (see
 * {@code UsernameOrder}), so a class split by roll number reads as it is written.
 */
@Entity
@Table(name = "exam_ta_assignments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExamTaAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "assignment_id")
    private Long assignmentId;

    @Column(name = "contest_id", nullable = false)
    private Long contestId;

    @Column(name = "ta_user_id", nullable = false)
    private Long taUserId;

    /** The question, by label; null for every question. */
    @Column(name = "problem_label", length = 16)
    private String problemLabel;

    /** First username in the range; null for "from the start". */
    @Column(name = "range_from", length = 50)
    private String rangeFrom;

    /** Last username in the range; null for "to the end". */
    @Column(name = "range_to", length = 50)
    private String rangeTo;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
