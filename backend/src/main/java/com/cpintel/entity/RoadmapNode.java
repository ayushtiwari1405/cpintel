package com.cpintel.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "roadmap_nodes")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RoadmapNode extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "node_id") private Long nodeId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false) private User user;

    @Column(name = "node_key", length = 60) private String nodeKey;
    @Column(name = "topic", nullable = false, length = 100) private String topic;
    @Column(name = "parent_topic", length = 100) private String parentTopic;
    @Column(name = "status", nullable = false, length = 20) @Builder.Default private String status = "LOCKED";
    @Column(name = "order_index") @Builder.Default private Integer orderIndex = 0;
    @Column(name = "min_difficulty") private Integer minDifficulty;
    @Column(name = "max_difficulty") private Integer maxDifficulty;
    @Column(name = "prereq_keys", length = 500) private String prereqKeys;
    @Column(name = "unlocked_at") private Instant unlockedAt;
    @Column(name = "completed_at") private Instant completedAt;
}
