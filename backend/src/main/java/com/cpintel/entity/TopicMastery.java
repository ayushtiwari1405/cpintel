package com.cpintel.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "topic_mastery",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "topic"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TopicMastery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mastery_id")
    private Long masteryId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "topic", nullable = false, length = 100)
    private String topic;

    @Column(name = "mastery_score")
    @Builder.Default
    private Double masteryScore = 0.0;

    @Column(name = "confidence_score")
    @Builder.Default
    private Double confidenceScore = 0.0;

    @Column(name = "revision_score")
    @Builder.Default
    private Double revisionScore = 0.0;

    @Column(name = "decay_score")
    @Builder.Default
    private Double decayScore = 0.0;

    @Column(name = "problems_solved")
    @Builder.Default
    private Integer problemsSolved = 0;

    @Column(name = "problems_attempted")
    @Builder.Default
    private Integer problemsAttempted = 0;

    @Column(name = "last_practiced_at")
    private Instant lastPracticedAt;

    @Column(name = "computed_at")
    private Instant computedAt;

    public enum MasteryBand {
        STRONG, MODERATE, WEAK, UNTOUCHED;

        public static MasteryBand from(double score) {
            if (score >= 80) return STRONG;
            if (score >= 50) return MODERATE;
            if (score >= 20) return WEAK;
            return UNTOUCHED;
        }
    }
}
