package com.cpintel.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "unified_scores")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UnifiedScore {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "score_id") private Long scoreId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    @JsonIgnore
    private User user;

    @Column(name = "cf_score") @Builder.Default private Double cfScore = 0.0;
    @Column(name = "lc_score") @Builder.Default private Double lcScore = 0.0;
    @Column(name = "cc_score") @Builder.Default private Double ccScore = 0.0;
    @Column(name = "unified_score") @Builder.Default private Double unifiedScore = 0.0;
    @Column(name = "cf_weight") @Builder.Default private Double cfWeight = 0.40;
    @Column(name = "lc_weight") @Builder.Default private Double lcWeight = 0.35;
    @Column(name = "cc_weight") @Builder.Default private Double ccWeight = 0.25;
    @Column(name = "computed_at") private Instant computedAt;
}
