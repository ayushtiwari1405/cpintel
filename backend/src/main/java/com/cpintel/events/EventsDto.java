package com.cpintel.events;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * The shapes contests and examinations are spoken about in.
 *
 * <p>Three audiences share this file, and what separates them is deliberate. An admin sees the
 * whole event, including who is assigned to it and what the monitor recorded about everybody. A
 * candidate sees the event they are sitting, their own clock and their own problems — never
 * another candidate's conduct, and never the roster. An invigilator's dashboard sees conduct,
 * but only for the examination they opened.
 *
 * <p>Where the same underlying row serves more than one of them, the fields the others must not
 * have live on a separate record rather than being nulled out on a shared one. A flag that has
 * to be remembered is a flag that will eventually be forgotten, and the thing forgotten here
 * would be one candidate's session leaking to another.
 */
public class EventsDto {

    // ------------------------------------------------------------ the event

    /**
     * What an examination asks a locked-down desktop client to do.
     *
     * <p>Every restriction is per event, because the requirement they come from is per event: a
     * supervised written examination and an open-book take-home are not the same room. They are
     * <em>requests</em> — the desktop client applies what its operating system will let it
     * apply, and reports what it could not. A browser applies almost none of it, which is why
     * an examination sat in a browser records that its monitoring was the weaker kind rather
     * than showing a suspiciously clean sheet.
     *
     * <p>None of this is proof of anything. It raises the cost of leaving the examination
     * environment; a second machine or a phone walks past all of it, and the honest framing
     * stays the one the event log already uses — these are observations, not verdicts.
     */
    public record DesktopPolicy(
        /** Keep the examination window in front of everything else. */
        boolean restrictWindowSwitching,
        /** Refuse navigation out of the examination interface. */
        boolean blockNavigation,
        /** Refuse to open other applications and unrelated sites through the app. */
        boolean blockExternalApps,
        /** Notice and report an attempt to leave the examination. */
        boolean detectLeavingExam,
        /** Notice and report an attempt to quit the examination application. */
        boolean detectAppTermination,
        /** Discard clipboard content that arrived from outside while the window was away. */
        boolean clipboardGuard
    ) {
        /** What an examination gets when nobody has said otherwise: watchful, not locked shut. */
        public static DesktopPolicy examDefault() {
            return new DesktopPolicy(false, true, true, true, true, true);
        }

        /** A contest is not an examination; nothing is restricted unless it is asked for. */
        public static DesktopPolicy contestDefault() {
            return new DesktopPolicy(false, false, false, false, false, false);
        }
    }

    /**
     * One event, as a list shows it.
     *
     * {@code lifecycle} is the effective one — what is stored, with the clock allowed to move a
     * scheduled event to active and an active one to ended. A caller never has to work that out
     * for itself, which is what stops two screens disagreeing about whether an examination has
     * started.
     */
    public record EventSummary(
        Long eventId,
        /** CONTEST or EXAM. */
        String kind,
        String platform,
        String externalId,
        String name,
        String description,
        String url,
        Instant startsAt,
        Instant endsAt,
        /** From the window, in seconds; null when the event has no window yet. */
        Long durationSeconds,
        /** DRAFT, SCHEDULED, ACTIVE, ENDED or ARCHIVED. */
        String lifecycle,
        /** PUBLIC, TEAMS or USERS. */
        String visibility,
        boolean lockdownRequired,
        int awayThresholdSeconds,
        /** The team that owns the event, when one does. */
        Long teamId,
        String teamName,
        int assignedTeams,
        int assignedUsers,
        /** Distinct people who may enter, teams expanded. */
        int participantCount,
        int problemCount,
        Instant standingsRefreshedAt,
        String standingsError
    ) {}

    public record ProblemRow(
        Long problemId,
        String label,
        String title,
        String externalId,
        int ordering,
        /** Points in a contest, marks in an examination. */
        Double points
    ) {}

    public record AssignedTeam(Long teamId, String name, int memberCount) {}

    public record AssignedUser(Long userId, String username, String fullName, boolean active) {}

    /** Everything about one event that an admin may see. */
    public record EventDetail(
        EventSummary event,
        String rules,
        DesktopPolicy desktopPolicy,
        List<String> allowedLanguages,
        List<ProblemRow> problems,
        List<AssignedTeam> teams,
        List<AssignedUser> users
    ) {}

    // ------------------------------------------------------------- admin writes

    public record ProblemRequest(
        @NotBlank @Size(max = 16) String label,
        @Size(max = 200) String title,
        @Size(max = 100) String externalId,
        Integer ordering,
        Double points
    ) {}

    public record ProblemsRequest(@NotNull List<ProblemRequest> problems) {}

    /**
     * A contest or examination, as an admin fills it in.
     *
     * <p>{@code kind} decides which rules apply to the rest: an examination may not be public,
     * and is monitored unless an admin deliberately says otherwise. Those are enforced in the
     * service rather than by two request shapes, so that changing an event's kind is one field
     * rather than a different endpoint.
     */
    public record EventRequest(
        /** CONTEST or EXAM. */
        @NotNull String kind,
        /** CODEFORCES or DOMJUDGE. Examinations run on DOMjudge. */
        @NotNull String platform,
        @NotBlank @Size(max = 100) String externalId,
        @NotBlank @Size(max = 200) String name,
        @Size(max = 2000) String description,
        @Size(max = 4000) String rules,
        @Size(max = 500) String url,
        Instant startsAt,
        Instant endsAt,
        /** PUBLIC, TEAMS or USERS. Ignored for an examination, which is never public. */
        String visibility,
        Boolean lockdownRequired,
        Integer awayThresholdSeconds,
        DesktopPolicy desktopPolicy,
        List<String> allowedLanguages,
        /** The team that owns this event, for the screens organised by team. */
        Long teamId,
        /** Teams that may enter. The owning team is added to these automatically. */
        List<Long> teamIds,
        /** People who may enter by name, whatever team they are in. */
        List<Long> userIds,
        List<ProblemRequest> problems
    ) {}

    public record AssignmentRequest(
        List<Long> teamIds,
        List<Long> userIds
    ) {}

    /** DRAFT, SCHEDULED, ACTIVE, ENDED or ARCHIVED — see the service for which moves are legal. */
    public record LifecycleRequest(@NotNull String lifecycle) {}

    // ------------------------------------------------- the candidate's view

    /**
     * An examination as the person sitting it sees it.
     *
     * Carries the clock, the problems, the rules, and the two things a candidate is owed before
     * being watched: that they are being watched, and exactly how long they may be away before
     * it is recorded. Monitoring somebody without telling them is a different and much worse
     * product.
     */
    public record MyExam(
        EventSummary event,
        String rules,
        List<ProblemRow> problems,
        DesktopPolicy desktopPolicy,
        List<String> allowedLanguages,
        /** Negative once the examination has begun. */
        long secondsUntilStart,
        /** Zero once it has ended. */
        long secondsRemaining,
        /** True once this candidate has entered it at least once. */
        boolean entered,
        /** This candidate's own submissions, as the event log counted them. */
        int mySubmissions,
        /** Their own place, when standings have been built. Never anyone else's. */
        Integer myRank,
        Integer mySolved,
        /**
         * Whether this paper asks for a password at all, and whether this device has given it.
         *
         * <p>Both are needed, and neither is inferable from the other. A paper with no password
         * is always unlocked, and a candidate who unlocked one on their own machine an hour ago
         * still has to be told that the paper is password-protected if they open it on another.
         *
         * <p>What this never carries is the password, or which half of it was wrong, or whether
         * a personal code exists for somebody else. {@code needsPasscode} is about this
         * candidate only, so that the field cannot become a way to learn how a paper is being
         * run for the person at the next desk.
         */
        boolean requiresPassword,
        boolean unlocked,
        /**
         * Which of the two the paper wants, separately.
         *
         * <p>{@code requiresPassword} is the union of these and cannot stand in for either: a
         * screen that had only the union would not know whether to draw one box or two, and
         * would have to make a candidate guess how many secrets they were meant to have been
         * handed. Which is the one thing an examination hall cannot afford.
         */
        boolean needsExamPassword,
        /** True when a code was issued to this candidate personally, so they must present it. */
        boolean needsPasscode,
        /** True when this candidate may read back the code they submitted. See ExamController. */
        boolean canReviewSubmissions
    ) {}

    /** What a candidate types to open a paper. Never logged, never echoed back. */
    public record UnlockRequest(
        /** The one the invigilator gives the room. Blank when the paper asks for no such thing. */
        @Size(max = 64) String examPassword,
        /** The one printed on this candidate's own slip. Blank when none was issued to them. */
        @Size(max = 64) String passcode
    ) {}

    // ------------------------------------------------- examination passwords

    /**
     * One candidate's code, as the screen that prints the desk slips sees it.
     *
     * <p>Admin-only, and the only shape in this file that carries a secret. It is deliberately
     * not a field on any candidate-facing record: a nullable "your code" on {@code MyExam}
     * would be one forgotten null check away from handing somebody the room's codes.
     */
    public record IssuedPasscode(
        Long userId,
        String username,
        String fullName,
        String code,
        Instant issuedAt,
        /** When this code first opened the paper, or null if it never has. */
        Instant firstUsedAt,
        int useCount
    ) {}

    /**
     * How an examination's passwords stand, without saying what any of them are.
     *
     * Safe to load with the rest of the admin detail screen; revealing the codes themselves is
     * a separate, audited request, because reading the room's passwords is an action somebody
     * took rather than a page that happened to render.
     */
    public record PasswordStatus(
        boolean keyConfigured,
        boolean examPasswordSet,
        Instant examPasswordSetAt,
        String examPasswordSetBy,
        int generation,
        int passcodesIssued,
        int participantCount
    ) {}

    /** What a candidate submitted into a past examination, for reading their own work back. */
    public record MySubmission(
        String id,
        Long externalId,
        String problemLabel,
        String problemName,
        String languageId,
        String languageLabel,
        String verdict,
        Instant submittedAt,
        Integer sourceBytes,
        /** Present only on the single-submission route; the list never carries source. */
        String source
    ) {}

    // ------------------------------------------------------- events and logs

    /** One event as the examination client reports it. */
    public record ClientEvent(
        /** Client-generated and stable across retries, so a dropped connection cannot inflate
         *  the log. */
        @NotBlank @Size(max = 64) String eventId,
        @NotBlank @Size(max = 40) String type,
        @Size(max = 16) String problemLabel,
        Long durationMs,
        @Size(max = 500) String detail,
        Instant occurredAt
    ) {}

    public record EventReport(@NotNull List<ClientEvent> events) {}

    public record LogEntry(
        Long id,
        Long userId,
        String username,
        String type,
        String problemLabel,
        Long durationMs,
        String detail,
        Instant occurredAt,
        Instant recordedAt
    ) {}

    public record LogPage(
        EventSummary event,
        List<LogEntry> entries,
        int page,
        int size,
        long total,
        int totalPages,
        /** Every type the log can hold, so the filter can be built without guessing. */
        List<String> types,
        /** How long events are kept, so a reader knows what an empty early window means. */
        int retentionDays
    ) {}

    // ---------------------------------------------------------- monitoring

    /**
     * One candidate on the invigilator's dashboard.
     *
     * <p>Every number here is an observation. {@code focusLosses} counts times the examination
     * window stopped being the one in front; {@code awayMs} adds up how long that lasted.
     * Neither is evidence of anything on its own, and the dashboard is written to keep that
     * visible rather than reducing them to a single score somebody would read as a verdict.
     */
    public record MonitorRow(
        Long userId,
        String username,
        String fullName,
        /** NOT_STARTED, ACTIVE, AWAY, SUBMITTED or LEFT. */
        String status,
        /** Whether something has reported from their machine in the last few seconds. */
        boolean monitorAlive,
        boolean focused,
        int focusLosses,
        long awayMs,
        Instant lastActivityAt,
        int submissions,
        String currentProblem,
        int problemsAttempted,
        int events
    ) {}

    public record MonitorSnapshot(
        EventSummary event,
        List<MonitorRow> rows,
        Instant generatedAt,
        int expected,
        int present,
        int away,
        int notStarted
    ) {}

    // ------------------------------------------------------------ analytics

    /** One person's history with the events they were assigned. */
    public record ParticipationRow(
        Long eventId,
        String kind,
        String name,
        String platform,
        Instant startsAt,
        Instant endsAt,
        String lifecycle,
        Integer rank,
        Integer groupSize,
        Integer solved,
        Integer penalty,
        Double score,
        boolean entered,
        int submissions,
        int focusLosses
    ) {}

    /** How a team has done across everything it has sat. */
    public record TeamAnalytics(
        Long teamId,
        String name,
        int memberCount,
        int events,
        int contests,
        int exams,
        /** Members who entered at least one event, over members assigned to any. */
        int participants,
        double participationRate,
        double averageSolved,
        double averageScore,
        int totalSolved,
        List<TeamEventRow> recent
    ) {}

    public record TeamEventRow(
        Long eventId,
        String kind,
        String name,
        Instant startsAt,
        String lifecycle,
        int ranked,
        double averageSolved,
        Integer bestRank,
        String bestMember
    ) {}
}
