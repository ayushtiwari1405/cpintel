package com.cpintel.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One event — a contest or an examination — run over an external judge.
 *
 * The contest itself belongs to Codeforces or DOMjudge: its clock, its problems and its real
 * scoreboard are all theirs. What is recorded here is CPIntel's arrangement around it — who may
 * enter, how it is watched, and where it is in its own life.
 *
 * <p><b>Contest or examination.</b> {@link Kind} is the only difference between the two
 * products that share this row. An examination is assigned rather than public, is monitored,
 * carries an away-time threshold and a desktop policy, and logs what happened during it; a
 * contest usually carries none of that. They are one table because everything downstream —
 * standings, the compete arena, the violation trail — is genuinely identical, and a second
 * table would have meant a second copy of all of it that drifted.
 *
 * <p><b>The team is optional.</b> An examination for four named candidates has no team behind
 * it. Participants are read from {@link ContestAssignment} rows; {@code group} is kept as the
 * team that owns the event, for the screens that are organised by team, and is null when
 * nobody owns it that way.
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

    /** The team this event belongs to, or null for one assigned to individuals. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private ContestGroup group;

    @Column(name = "kind", nullable = false, length = 10)
    @Builder.Default
    private String kind = Kind.CONTEST.name();

    @Column(name = "platform", nullable = false, length = 20)
    private String platform;

    /** A Codeforces contest id, or a DOMjudge contest id. Opaque on purpose. */
    @Column(name = "external_id", nullable = false, length = 100)
    private String externalId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", length = 2000)
    private String description;

    /** Whatever the people sitting it are told about how it is run. Free text, shown verbatim. */
    @Column(name = "rules", length = 4000)
    private String rules;

    @Column(name = "url", length = 500)
    private String url;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "lifecycle", nullable = false, length = 12)
    @Builder.Default
    private String lifecycle = Lifecycle.DRAFT.name();

    @Column(name = "visibility", nullable = false, length = 10)
    @Builder.Default
    private String visibility = Visibility.TEAMS.name();

    @Column(name = "lockdown_required", nullable = false)
    @Builder.Default
    private Boolean lockdownRequired = true;

    /**
     * How long someone may be away from the window before it is worth saying so and recording
     * it, in seconds.
     *
     * Per event rather than global: what counts as leaving a two-hour written examination is
     * not what counts as leaving a five-hour team contest, and an admin running both needs to
     * be able to say so.
     */
    @Column(name = "away_threshold_seconds", nullable = false)
    @Builder.Default
    private Integer awayThresholdSeconds = 10;

    /** The desktop restrictions this event asks the locked-down client to apply, as JSON. */
    @Column(name = "desktop_policy", columnDefinition = "TEXT")
    private String desktopPolicy;

    /** Comma-separated language ids this event accepts, or null for whatever the judge allows. */
    @Column(name = "allowed_languages", length = 500)
    private String allowedLanguages;

    @Column(name = "archived_at")
    private Instant archivedAt;

    /**
     * The password that opens this examination, encrypted at rest.
     *
     * <p>One string for the whole paper, handed out by whoever starts it. Null means no shared
     * password has been generated, and an examination with none does not ask for one — an
     * admin who has not generated it has not decided to require it, and inventing a
     * requirement they did not ask for would lock a room out of a paper that was about to
     * start.
     *
     * <p>Encrypted rather than hashed because the invigilator has to read it back; see
     * {@link com.cpintel.events.ExamPasswordService}. Never serialised into any DTO the
     * candidate side can reach.
     */
    @Column(name = "exam_password", length = 500)
    private String examPassword;

    @Column(name = "exam_password_set_at")
    private Instant examPasswordSetAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_password_set_by")
    private User examPasswordSetBy;

    /**
     * Bumped every time the password is regenerated.
     *
     * <p>An access grant records the generation it was issued under, so rotating a password
     * that has leaked ends every session opened with the old one and nothing else. Without it
     * rotation would only affect people who had not got in yet, which is the opposite of who
     * it is for.
     */
    @Column(name = "exam_password_gen", nullable = false)
    @Builder.Default
    private Integer examPasswordGen = 0;

    @Column(name = "standings_refreshed_at")
    private Instant standingsRefreshedAt;

    /** Why the last refresh failed, kept so the admin screen can say so rather than show stale
     *  numbers as if they were current. */
    @Column(name = "standings_error", length = 500)
    private String standingsError;

    @OneToMany(mappedBy = "contest", cascade = CascadeType.ALL, orphanRemoval = true,
        fetch = FetchType.LAZY)
    @Builder.Default
    private List<ContestProblem> problems = new ArrayList<>();

    public enum Platform { CODEFORCES, DOMJUDGE }

    public enum Kind { CONTEST, EXAM }

    /**
     * Who may see the event at all.
     *
     * An examination is never PUBLIC — that is enforced in the service rather than here,
     * because the constraint is about what an examination <em>is</em>, and a check constraint
     * spanning two columns would be the wrong place to explain it.
     */
    public enum Visibility { PUBLIC, TEAMS, USERS }

    /**
     * Where the event is in its own life.
     *
     * DRAFT and ARCHIVED are stored because nothing about a clock can imply them: a fully
     * configured event with a future start is indistinguishable from a half-written one, and an
     * event kept for the record is indistinguishable from one that merely finished. The two
     * states in between are derived from the window — see {@link #effectiveLifecycle} — because
     * a stored "ACTIVE" is wrong for exactly as long as whatever updates it is down, and that
     * is during the event, which is the only time anybody is looking.
     */
    public enum Lifecycle { DRAFT, SCHEDULED, ACTIVE, ENDED, ARCHIVED }

    /** True while the contest window is open, which is when the lock is expected to be held. */
    public boolean isLive(Instant now) {
        if (startsAt == null || endsAt == null) return false;
        return !now.isBefore(startsAt) && now.isBefore(endsAt);
    }

    public boolean isExam() {
        return Kind.EXAM.name().equals(kind);
    }

    /**
     * The lifecycle as of now: what is stored, with the clock allowed to move it along.
     *
     * DRAFT and ARCHIVED are answers in themselves and the clock cannot override them — an
     * event nobody has finished writing does not become live because its start time passed.
     * Between those two, the window decides.
     */
    public Lifecycle effectiveLifecycle(Instant now) {
        Lifecycle stored = lifecycleOrDefault();
        if (stored == Lifecycle.DRAFT || stored == Lifecycle.ARCHIVED) return stored;
        if (startsAt == null || endsAt == null) return Lifecycle.SCHEDULED;
        if (now.isBefore(startsAt)) return Lifecycle.SCHEDULED;
        return now.isBefore(endsAt) ? Lifecycle.ACTIVE : Lifecycle.ENDED;
    }

    private Lifecycle lifecycleOrDefault() {
        try {
            return Lifecycle.valueOf(lifecycle);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Lifecycle.SCHEDULED;
        }
    }

    /**
     * Whether people may enter and submit right now.
     *
     * Both halves matter: a draft that happens to be inside its window must stay shut, and an
     * event whose window has closed must not reopen because somebody left it marked active.
     */
    public boolean isOpenForParticipation(Instant now) {
        return effectiveLifecycle(now) == Lifecycle.ACTIVE;
    }
}
