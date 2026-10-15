package com.cpintel.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "token_id")
    private Long tokenId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** SHA-256 of the token, in hex; the token itself is never stored (V23). */
    @Column(name = "token", nullable = false, unique = true, length = 500)
    private String token;

    /** Shared by every token renewed from one sign-in, so a stolen one can end them all. */
    @Column(name = "family_id", nullable = false, length = 36)
    private String familyId;

    /** When this token was exchanged for its successor; null if it never was. */
    @Column(name = "rotated_at")
    private Instant rotatedAt;

    @Column(name = "device_info", length = 500)
    private String deviceInfo;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    /**
     * The examination this token's session was signed in for, or null for an ordinary one.
     * Renewing keeps the session what it was; see V13__exam_mode_sessions.sql.
     */
    @Column(name = "exam_id")
    private Long examId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked", nullable = false)
    @Builder.Default
    private Boolean revoked = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
