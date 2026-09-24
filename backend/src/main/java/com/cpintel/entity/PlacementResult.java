package com.cpintel.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** One attempt at the placement gauntlet. See {@code V11__placement_results.sql}. */
@Entity
@Table(name = "placement_results")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlacementResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "result_id")
    private Long resultId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "overall_rating", nullable = false)
    private Integer overallRating;

    /** Per-area results, as JSON. */
    @Column(name = "sections", nullable = false, columnDefinition = "TEXT")
    private String sections;

    @Column(name = "nodes_placed", nullable = false)
    private Integer nodesPlaced;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
