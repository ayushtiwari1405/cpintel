package com.cpintel.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "revision_schedule",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "topic"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RevisionSchedule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "revision_id")
    private Long revisionId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "topic", nullable = false, length = 100)
    private String topic;

    @Column(name = "next_revision_at", nullable = false)
    private Instant nextRevisionAt;

    @Column(name = "revision_priority")
    @Builder.Default
    private Integer revisionPriority = 50;

    @Column(name = "decay_score")
    @Builder.Default
    private Double decayScore = 0.0;

    @Column(name = "interval_days")
    @Builder.Default
    private Integer intervalDays = 1;

    @Column(name = "repetition_count")
    @Builder.Default
    private Integer repetitionCount = 0;

    @Column(name = "ease_factor")
    @Builder.Default
    private Double easeFactor = 2.5;

    @Column(name = "last_revised_at")
    private Instant lastRevisedAt;
}
