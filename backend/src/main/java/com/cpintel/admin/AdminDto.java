package com.cpintel.admin;

import com.cpintel.dto.PlatformDto;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * The shapes the admin console speaks in.
 *
 * Deliberately narrower than the entities behind them. An admin needs to see who someone is
 * and what state their account is in; they have no business reading password hashes, tokens,
 * or the contents of anyone's personal files, so none of that has a field here.
 */
public class AdminDto {

    // ------------------------------------------------------------------ users

    /**
     * One row of the user table. {@code lastLoginAt} comes from the audit trail rather than a
     * column on the user, so it is only as old as the audit log itself — accounts that last
     * signed in before auditing existed read as null rather than as never having logged in.
     */
    public record UserRow(
        Long userId,
        String username,
        String email,
        String fullName,
        String role,
        boolean active,
        boolean verified,
        Instant createdAt,
        Instant lastLoginAt,
        /**
         * When the owner last set this password themselves, or null if they never have.
         *
         * Shown because null is a fact rather than a gap: until it is set, the password on the
         * account is the one somebody else typed when they created it, and whoever that was
         * still knows it. An admin looking at a roster of nulls is looking at a room that has
         * not been asked to change its passwords.
         */
        Instant passwordChangedAt
    ) {}

    public record UserPage(
        List<UserRow> users,
        int page,
        int size,
        long total,
        int totalPages
    ) {}

    public record UserDetail(
        UserRow user,
        String country,
        String institution,
        String avatarUrl,
        List<PlatformDto.Summary> platforms,
        long fileCount,
        long fileBytes,
        List<AuditEntry> recentActivity
    ) {}

    public record RoleRequest(@NotNull String role) {}

    /**
     * A new account, as a super admin fills it in.
     *
     * The same constraints as the public sign-up form, so an account created here cannot be
     * weaker than one someone would have made themselves.
     */
    public record CreateUserRequest(
        @NotBlank @Size(min = 3, max = 50)
        @Pattern(regexp = "^[a-zA-Z0-9_]+$",
                 message = "Username can only contain letters, numbers and underscores")
        String username,

        @NotBlank @Email @Size(max = 255)
        String email,

        @NotBlank @Size(min = 8, message = "Password must be at least 8 characters")
        String password,

        @Size(max = 100)
        String fullName,

        /** USER or ADMIN. Absent means USER. */
        String role
    ) {}

    /**
     * The parts of somebody's profile an admin may correct.
     *
     * Deliberately not the password, the role or the active flag: each of those has its own
     * endpoint with its own rules, and folding them into a general profile update would put
     * "promote to admin" one forgotten field away from "fix a typo in a surname".
     */
    public record UpdateUserRequest(
        @Size(max = 100) String fullName,
        @Email @Size(max = 255) String email,
        @Size(max = 200) String institution,
        @Size(max = 100) String country
    ) {}

    public record ActiveRequest(
        @NotNull Boolean active,
        @Size(max = 200) String reason
    ) {}

    /**
     * An administrator setting somebody else's password for them.
     *
     * <p>For the person who has forgotten theirs and cannot reach their own mail, which on a
     * deployment handing out accounts on a sheet of paper is a common enough morning. The
     * password is chosen or generated here and handed over the same way the first one was; it
     * is never emailed, because a live credential in a mailbox is exactly what the reset-link
     * flow exists to avoid.
     *
     * <p>Leave {@code password} blank to have one generated, which is the better path — a
     * generated password is returned once and is not one the administrator was already
     * thinking of.
     */
    public record SetPasswordRequest(
        @Size(min = 8, max = 200, message = "A password has to be at least 8 characters")
        String password,
        @Size(max = 200) String reason
    ) {}

    /** The password an administrator just set, shown once and stored only as a hash. */
    public record GeneratedPassword(Long userId, String username, String password) {}

    // --------------------------------------------------------------- overview

    /**
     * Everything the landing screen shows, in one round trip. The counts are cheap aggregates;
     * none of them scan a table the way a report would, so this stays fast as the user count
     * grows and is safe to poll.
     */
    public record Overview(
        UserStats users,
        SyncStats sync,
        FileStats files,
        PolicyStats policy,
        List<AuditEntry> recentActivity
    ) {}

    public record UserStats(
        long total,
        long active,
        long inactive,
        long admins,
        long verified,
        long joinedLast7Days
    ) {}

    public record SyncStats(
        long queued,
        long running,
        long failedLast24h,
        long completedLast24h
    ) {}

    /** Sizes only. Nothing here reveals a file's name, let alone its contents. */
    public record FileStats(
        long files,
        long bytes,
        long owners,
        boolean available
    ) {}

    public record PolicyStats(
        boolean contestFilesEnabledByDefault,
        boolean defaultFromConfig,
        int contestExceptions
    ) {}

    // ------------------------------------------------------------------ audit

    public record AuditEntry(
        Long logId,
        Long userId,
        String username,
        String action,
        String entityType,
        String entityId,
        String ipAddress,
        String userAgent,
        Instant createdAt
    ) {}

    public record AuditPage(
        List<AuditEntry> entries,
        int page,
        int size,
        long total,
        int totalPages,
        List<String> actions
    ) {}
}
