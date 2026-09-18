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
        Instant lastLoginAt
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

    public record ActiveRequest(
        @NotNull Boolean active,
        @Size(max = 200) String reason
    ) {}

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
