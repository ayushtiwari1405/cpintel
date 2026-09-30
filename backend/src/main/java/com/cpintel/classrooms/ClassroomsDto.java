package com.cpintel.classrooms;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class ClassroomsDto {

    private ClassroomsDto() {}

    public record ClassroomRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String description,
        /** The judge's root, e.g. https://judge.example.edu — without /api. */
        @NotBlank @Size(max = 500) String domjudgeUrl,
        @Size(max = 100) String serviceUsername,
        /**
         * Write-only. Null keeps the password already stored; an empty string clears the service
         * account. Never returned by any endpoint.
         */
        @Size(max = 200) String servicePassword
    ) {}

    /** What an admin sees. No secret, and no ciphertext either. */
    public record ClassroomSummary(
        Long classroomId,
        String name,
        String description,
        String domjudgeUrl,
        String serviceUsername,
        boolean hasServiceAccount,
        boolean active,
        Long ownerId,
        long memberCount,
        long groupCount,
        long eventCount,
        Instant createdAt
    ) {}

    /** What a student sees of the classrooms they are in. */
    public record MyClassroom(
        Long classroomId,
        String name,
        String description,
        /** Whether a DOMjudge login is attached for them here, so they can submit. */
        boolean judgeLoginAttached,
        String domjudgeUsername
    ) {}

    public record Member(
        Long userId,
        String username,
        String fullName,
        String email,
        String domjudgeUsername,
        boolean judgeLoginAttached,
        Instant joinedAt
    ) {}

    public record MemberRequest(@NotNull Long userId) {}

    public record StaffMember(Long userId, String username, String fullName, boolean owner) {}

    public record StaffRequest(@NotNull Long userId) {}

    /** The result of checking a classroom's judge. */
    public record JudgeCheck(boolean reachable, String version, String message) {}
}
