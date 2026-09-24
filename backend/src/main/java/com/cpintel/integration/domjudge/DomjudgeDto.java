package com.cpintel.integration.domjudge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * DTOs for attaching a DOMjudge account to a CPIntel one, and for what that account can see.
 *
 * <p>Nothing here ever carries a password outward. {@link ProvisionRequest} takes one because
 * that is the only way to attach an account, and no other record in this file has a field for
 * one — a status response that echoed the stored password back to an admin screen would put it
 * in a browser cache, a proxy log and a screenshot, which is exactly the exposure the store is
 * built to bound.
 */
public class DomjudgeDto {

    /**
     * An admin attaching one contestant's DOMjudge login.
     *
     * The contestant does not do this themselves, and the shape says so: {@code userId} is the
     * CPIntel account the credentials belong to, supplied by whoever is doing the attaching.
     */
    public record ProvisionRequest(
        @NotNull Long userId,
        @NotBlank @Size(max = 100) String username,
        @NotBlank @Size(max = 200) String password,
        /** The contestant's display name, as the admin knows them. */
        @Size(max = 200) String name,
        /**
         * The team the admin puts them in, or null to follow the judge.
         *
         * Optional on purpose. Leaving it blank is the right answer whenever the DOMjudge
         * account is already on the correct team, which is the common case — and it is the
         * only answer available on a build that will not list teams to this account.
         */
        @Size(max = 100) String teamId
    ) {}

    /** A new password for the DOMjudge login already attached to a user. */
    public record PasswordChangeRequest(
        @NotBlank @Size(max = 200) String password
    ) {}

    /**
     * New passwords for many attached logins at once, pasted as {@code djUsername,djPassword}.
     *
     * Sent whole, like a roster, so the preview the admin approves is produced by the same code
     * that commits it.
     */
    public record BulkPasswordRequest(
        @NotBlank @Size(max = 200_000) String text,
        /** True to verify every row and write nothing. */
        Boolean dryRun
    ) {}

    /** One team an admin may put somebody in. */
    public record TeamOption(String id, String name) {}

    /**
     * Whether a CPIntel account has a DOMjudge login attached, and which team it competes for.
     *
     * {@code teamName} is the field that matters on screen. A contestant needs to be able to
     * confirm they are about to submit as the right team before the round starts, because
     * discovering it afterwards means the submission is already on somebody else's board.
     */
    public record AccountStatus(
        boolean linked,
        String username,
        String name,
        /** The team DOMjudge itself attributes this account's submissions to. */
        String teamId,
        String teamName,
        /** The team the admin put them in, or null when they left it to the judge. */
        String assignedTeamId,
        String assignedTeamName,
        /**
         * True when the admin's choice disagrees with the judge's.
         *
         * Worth surfacing rather than silently resolving: it means this person's submissions
         * will appear under one team on the judge's board and be grouped under another in
         * CPIntel. That is occasionally deliberate and usually a mistake, and only the admin
         * looking at it can tell which.
         */
        boolean teamMismatch,
        Instant provisioned,
        /** Null when nothing is attached, or when the store could not report an expiry. */
        Long expiresInSeconds
    ) {
        public static AccountStatus unlinked() {
            return new AccountStatus(false, null, null, null, null, null, null,
                false, null, null);
        }
    }

    /**
     * One contest this account may enter.
     *
     * Read as the contestant rather than as the deployment, so the list is what DOMjudge is
     * willing to show <em>them</em>. A contest nobody registered them for does not appear, and
     * that is the point: the picker offers rounds someone can actually sit rather than every
     * round on the instance.
     */
    public record ContestSummary(
        String id,
        String name,
        /** BEFORE, CODING or FINISHED — the same vocabulary the arena already renders. */
        String phase,
        boolean running,
        Instant startsAt,
        Instant endsAt,
        long durationSeconds,
        /** Negative once the contest has begun. */
        long secondsUntilStart
    ) {}
}
