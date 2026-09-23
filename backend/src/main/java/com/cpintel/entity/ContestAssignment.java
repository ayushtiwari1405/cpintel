package com.cpintel.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One team, or one person, who may enter an event.
 *
 * Exactly one of {@code group} and {@code user} is set; the database enforces it. A row that
 * named both would have two meanings and no way of saying which one an admin had removed.
 *
 * <p>Assignments are additive and read as a union: someone reaches an examination because their
 * team was assigned, or because they were named directly, and being named twice is the same as
 * being named once. That is deliberate — an admin adding the four candidates who missed the
 * first sitting should not have to think about whether any of them is also in the class.
 */
@Entity
@Table(name = "contest_assignments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContestAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "assignment_id")
    private Long assignmentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contest_id", nullable = false)
    private GroupContest contest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private ContestGroup group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** The admin who made the assignment, for the audit trail's benefit. */
    @Column(name = "assigned_by")
    private Long assignedBy;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @PrePersist
    void onCreate() {
        if (assignedAt == null) assignedAt = Instant.now();
    }
}
