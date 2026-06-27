package com.cpintel.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "platform_accounts",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "platform"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlatformAccount extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "account_id")
    private Long accountId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "platform", nullable = false, length = 20)
    private String platform;

    @Column(name = "handle", nullable = false, length = 100)
    private String handle;

    @Column(name = "current_rating")
    private Integer currentRating;

    @Column(name = "max_rating")
    private Integer maxRating;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "sync_status", length = 20)
    @Builder.Default
    private String syncStatus = "PENDING";

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    public enum Platform {
        CODEFORCES, LEETCODE, CODECHEF
    }

    public enum SyncStatus {
        PENDING, RUNNING, COMPLETED, FAILED
    }
}
