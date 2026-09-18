package com.cpintel.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One member's cached position in a group contest.
 *
 * A snapshot rather than a live read: building it costs a rate-limited call per member on
 * Codeforces, so it is refreshed on a schedule and on demand, and every screen that shows it
 * also shows when it was taken.
 */
@Entity
@Table(name = "group_standings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupStanding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "row_id")
    private Long rowId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contest_id", nullable = false)
    private GroupContest contest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "handle", length = 100)
    private String handle;

    /** Rank within the group — the number this whole feature exists to produce. */
    @Column(name = "group_rank")
    private Integer groupRank;

    @Column(name = "solved", nullable = false)
    @Builder.Default
    private Integer solved = 0;

    @Column(name = "penalty", nullable = false)
    @Builder.Default
    private Integer penalty = 0;

    @Column(name = "score", precision = 10, scale = 2)
    private java.math.BigDecimal score;

    /** Per-problem detail as JSON, so one column serves judges with different problem models. */
    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    /**
     * False when the member could not be found on the external board.
     *
     * Normal before their first submission, and worth surfacing after it — an unfound member
     * usually means a handle that does not match, not someone who solved nothing.
     */
    @Column(name = "found", nullable = false)
    @Builder.Default
    private Boolean found = false;

    @Column(name = "computed_at", nullable = false)
    @Builder.Default
    private Instant computedAt = Instant.now();
}
