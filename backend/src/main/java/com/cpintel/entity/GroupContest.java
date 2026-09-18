package com.cpintel.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One external contest a group sits together.
 *
 * The contest itself belongs to Codeforces or DOMjudge — its clock, its problems and its real
 * scoreboard are all theirs. This row records which one, who is being measured on it, and
 * whether the desktop lock is required to sit it.
 */
@Entity
@Table(name = "group_contests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupContest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "contest_id")
    private Long contestId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private ContestGroup group;

    @Column(name = "platform", nullable = false, length = 20)
    private String platform;

    /** A Codeforces contest id, or a DOMjudge contest id. Opaque on purpose. */
    @Column(name = "external_id", nullable = false, length = 100)
    private String externalId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "url", length = 500)
    private String url;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "lockdown_required", nullable = false)
    @Builder.Default
    private Boolean lockdownRequired = true;

    @Column(name = "standings_refreshed_at")
    private Instant standingsRefreshedAt;

    /** Why the last refresh failed, kept so the admin screen can say so rather than show stale
     *  numbers as if they were current. */
    @Column(name = "standings_error", length = 500)
    private String standingsError;

    public enum Platform { CODEFORCES, DOMJUDGE }

    /** True while the contest window is open, which is when the lock is expected to be held. */
    public boolean isLive(Instant now) {
        if (startsAt == null || endsAt == null) return false;
        return !now.isBefore(startsAt) && now.isBefore(endsAt);
    }
}
