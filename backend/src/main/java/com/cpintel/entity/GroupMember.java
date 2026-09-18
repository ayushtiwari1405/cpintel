package com.cpintel.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "group_members")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id")
    private Long memberId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private ContestGroup group;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * How this person appears on the external judge, when it cannot be derived.
     *
     * Codeforces handles come from the linked platform account, so this stays null for most
     * members. DOMjudge has no equivalent link in CPIntel, so a team name recorded here is the
     * only way to find someone on that board.
     */
    @Column(name = "external_handle", length = 100)
    private String externalHandle;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @PrePersist
    void onCreate() {
        if (joinedAt == null) joinedAt = Instant.now();
    }
}
