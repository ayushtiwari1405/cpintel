package com.cpintel.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A student enrolled in a classroom.
 *
 * <p>The judge login itself is a secret and lives in Redis ({@code DomjudgeCredentialStore});
 * this row is the half that is not, so an admin can see who is linked to which judge without
 * decrypting anything, and so enrolment outlives a login's TTL.
 */
@Entity
@Table(name = "classroom_members")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClassroomMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "classroom_member_id")
    private Long classroomMemberId;

    @Column(name = "classroom_id", nullable = false)
    private Long classroomId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** The DOMjudge login attached for this classroom, or null when none is. */
    @Column(name = "domjudge_username", length = 100)
    private String domjudgeUsername;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @PrePersist
    void onCreate() {
        if (joinedAt == null) joinedAt = Instant.now();
    }
}
