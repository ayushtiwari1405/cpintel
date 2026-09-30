package com.cpintel.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A student enrolled in a classroom.
 *
 * <p>It also holds the student's DOMjudge login for this classroom, sealed, so the login lives
 * exactly as long as the enrolment and is backed up with it. The username beside it is kept in
 * the clear so an admin can see who is linked where without decrypting anything.
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

    /**
     * The login itself, sealed ({@code SecretBox}), or null when none is attached. Read and
     * written only through {@code DomjudgeCredentialStore}.
     */
    @Column(name = "domjudge_login")
    private String domjudgeLogin;

    @Column(name = "domjudge_attached_at")
    private Instant domjudgeAttachedAt;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @PrePersist
    void onCreate() {
        if (joinedAt == null) joinedAt = Instant.now();
    }
}
