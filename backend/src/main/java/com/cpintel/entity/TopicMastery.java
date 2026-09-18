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

    /**
     * Identity of the thing being scored: a skill-tree node id when {@link #scope} is
     * {@code NODE}, a coarse roll-up name when it is {@code TOPIC}. The two namespaces cannot
     * collide — node ids are kebab-case, roll-up names are title-case words — so they share the
     * unique constraint on (user_id, topic).
     */
    @Column(name = "topic", nullable = false, length = 120)
    private String topic;

    /**
     * {@code NODE} for one skill-tree node, {@code TOPIC} for a roll-up derived from nodes.
     *
     * <p>Both live in one table because everything downstream — scoring, decay, the revision
     * queue — treats them identically; only the reader decides which granularity it wants.
     */
    @Column(name = "scope", nullable = false, length = 10)
    @Builder.Default
    private String scope = Scope.TOPIC;

    /** For a NODE row, the roll-up topic it contributes to. Null on a TOPIC row. */
    @Column(name = "parent_topic", length = 100)
    private String parentTopic;

    /** For a NODE row, the tree track it belongs to. Null on a TOPIC row. */
    @Column(name = "track", length = 60)
    private String track;

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

    /** Values of {@link #scope}. A plain constant holder rather than an enum: the column is
     *  written by a native upsert, which cannot bind a Java enum. */
    public static final class Scope {
        private Scope() {}
        public static final String NODE = "NODE";
        public static final String TOPIC = "TOPIC";
    }

    public boolean isNode() { return Scope.NODE.equals(scope); }

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
