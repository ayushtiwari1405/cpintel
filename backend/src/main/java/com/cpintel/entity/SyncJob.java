package com.cpintel.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "sync_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SyncJob extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "job_id")
    private Long jobId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "platform", nullable = false, length = 20)
    private String platform;

    @Column(name = "job_type", length = 30)
    @Builder.Default
    private String jobType = "INCREMENTAL";

    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "QUEUED";

    @Column(name = "progress_pct")
    @Builder.Default
    private Double progressPct = 0.0;

    @Column(name = "items_synced")
    @Builder.Default
    private Integer itemsSynced = 0;

    @Column(name = "error_msg", length = 1000)
    private String errorMsg;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public enum Status {
        QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED
    }
}
