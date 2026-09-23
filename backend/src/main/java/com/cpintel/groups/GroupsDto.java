package com.cpintel.groups;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * The shapes the group-contest feature speaks in.
 *
 * Two audiences share this file deliberately. An admin sees the whole group; a participant sees
 * their own row and their own contests. Where the same record serves both, the fields an admin
 * needs and a participant does not — other people's violation counts, for instance — are on a
 * separate record rather than nulled out, so it is impossible to leak one into the other by
 * forgetting a flag.
 */
public class GroupsDto {

    // ------------------------------------------------------------------ groups

    public record GroupSummary(
        Long groupId,
        String name,
        String description,
        boolean active,
        int memberCount,
        int contestCount,
        Instant createdAt
    ) {}

    public record Member(
        Long userId,
        String username,
        String email,
        String fullName,
        /** The Codeforces handle from their linked account, when they have one. */
        String codeforcesHandle,
        /** The override recorded on the membership, used where no account can be derived. */
        String externalHandle,
        boolean active,
        Instant joinedAt
    ) {}

    public record GroupDetail(
        GroupSummary group,
        List<Member> members,
        List<ContestSummary> contests
    ) {}

    public record GroupRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String description
    ) {}

    /**
     * A pasted roster.
     *
     * <p>{@code text} is the raw paste, CSV or tab-separated; the parser works out which. It is
     * sent whole rather than pre-parsed by the browser so that one implementation decides what
     * a roster means, and the preview the admin approves is produced by the same code that
     * commits it.
     */
    public record RosterImportRequest(
        @NotBlank @Size(max = 200_000) String text,
        /** True to report the plan and write nothing. */
        Boolean dryRun,
        /**
         * The team to put this whole import on, or null to take it from the paste.
         *
         * Applied only to rows that do not carry a team of their own, so a roster that already
         * names teams per person keeps them. The common case is the opposite — a class list
         * with no team column at all, every one of whom is on the same team — and typing that
         * team into two hundred rows is not a reasonable thing to ask of anybody.
         */
        @Size(max = 100) String teamName
    ) {}

    /** Where a member is moving to. The team they are leaving is in the path. */
    public record MoveMemberRequest(@NotNull Long targetGroupId) {}

    public record MemberRequest(
        @NotNull Long userId,
        @Size(max = 100) String externalHandle
    ) {}

    // ---------------------------------------------------------------- contests

    public record ContestSummary(
        Long contestId,
        /** CONTEST or EXAM — the same row carries both, see {@code GroupContest}. */
        String kind,
        Long groupId,
        String groupName,
        String platform,
        String externalId,
        String name,
        String url,
        Instant startsAt,
        Instant endsAt,
        boolean lockdownRequired,
        /** How long someone may be away before it is recorded, in seconds. */
        int awayThresholdSeconds,
        /** SCHEDULED, LIVE or FINISHED, derived from the window rather than stored. */
        String status,
        /** DRAFT, SCHEDULED, ACTIVE, ENDED or ARCHIVED — the event's own life. */
        String lifecycle,
        Instant standingsRefreshedAt,
        String standingsError
    ) {}

    public record ContestRequest(
        @NotNull String platform,
        @NotBlank @Size(max = 100) String externalId,
        @NotBlank @Size(max = 200) String name,
        @Size(max = 500) String url,
        Instant startsAt,
        Instant endsAt,
        Boolean lockdownRequired
    ) {}

    // --------------------------------------------------------------- standings

    /**
     * One row of the group board.
     *
     * `groupRank` is the number the whole feature exists to produce — where someone stands
     * among the people they are actually being measured against, which is rarely close to
     * where they stand on the external scoreboard.
     */
    public record StandingRow(
        Integer groupRank,
        Long userId,
        String username,
        String handle,
        int solved,
        int penalty,
        Double score,
        /** Per-problem detail, shaped by the judge it came from. */
        String detail,
        /** False when this member could not be found on the external board at all. */
        boolean found,
        /** Null for a participant's own view of the board, which carries no policing. */
        ViolationSummary violations
    ) {}

    public record Standings(
        ContestSummary contest,
        List<StandingRow> rows,
        Instant refreshedAt,
        String error,
        /** Members who could not be matched to anyone on the external board. */
        List<String> unmatched
    ) {}

    // -------------------------------------------------------------- violations

    /**
     * What the desktop lock reported, as counts.
     *
     * Deliberately factual. A focus loss is a focus loss — whether it was a cheat, a
     * notification or someone answering the door is not something the software can know, and
     * calling the total a "score" would invite exactly that reading.
     */
    public record ViolationSummary(
        int focusLosses,
        long awayMs,
        int clipboardWipes,
        int blockedActions,
        boolean lockdownUnavailable,
        boolean lockdownPartial,
        boolean lockdownReleased,
        int total
    ) {}

    public record ViolationEntry(
        Long violationId,
        Long userId,
        String username,
        String type,
        String detail,
        Long durationMs,
        Instant occurredAt,
        Instant reportedAt
    ) {}

    public record ViolationFeed(
        ContestSummary contest,
        List<ViolationEntry> entries,
        long total
    ) {}

    /** One event as the desktop client reports it. */
    public record ViolationEvent(
        /** Client-generated and stable across retries, so a flaky connection cannot inflate
         *  the count. */
        @NotBlank @Size(max = 64) String eventId,
        @NotBlank @Size(max = 40) String type,
        @Size(max = 500) String detail,
        Long durationMs,
        Instant occurredAt
    ) {}

    public record ViolationReport(
        @NotNull List<ViolationEvent> events
    ) {}

    // ------------------------------------------------------ participant's view

    /**
     * What a participant is told about a contest they are sitting.
     *
     * Their own position and nothing about anyone's conduct. Whether they are being watched is
     * stated plainly through `lockdownRequired`, because a lock that reports on someone
     * without telling them is a different and much worse product.
     */
    public record MyContest(
        ContestSummary contest,
        Integer myRank,
        Integer groupSize,
        Integer mySolved
    ) {}
}
