package com.cpintel.files;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * DTOs for personal files — the user's own reference material, reachable from inside a
 * running contest.
 */
public class FilesDto {

    /** One file, without its bytes. Everything the list and the panel header need. */
    public record FileMeta(
        String id,
        String name,
        String label,
        String contentType,
        int sizeBytes,
        /** True when the panel can render it as text; false means download-only. */
        boolean textual,
        String sourceHash,
        Instant uploadedAt,
        Instant updatedAt
    ) {}

    /**
     * The whole vault plus the limits it is held to.
     *
     * The limits travel with the listing so the UI can refuse an oversized file before
     * spending a contest minute uploading it.
     */
    public record Vault(
        List<FileMeta> files,
        long usedBytes,
        long maxTotalBytes,
        int maxFileBytes,
        int maxFiles,
        List<String> allowedExtensions
    ) {}

    /**
     * A file opened for reading.
     *
     * {@code text} is null for anything not safe to render — the UI offers a download
     * instead. {@code truncated} marks a text file clipped to the preview cap; the download
     * always carries the whole thing.
     */
    public record FileContent(
        FileMeta file,
        String text,
        boolean truncated,
        String notice
    ) {}

    /** Raw bytes on their way to a download response. */
    public record Download(String name, String contentType, byte[] bytes) {}

    /**
     * A whole-record edit, not a partial one: both fields are stored as sent, so an omitted
     * label clears the label rather than leaving the old one in place.
     */
    public record RenameRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 200) String label
    ) {}

    /** An admin's per-contest decision. */
    public record ContestRule(
        String platform,
        String contestId,
        boolean enabled,
        String note,
        Long updatedBy,
        Instant updatedAt
    ) {}

    /**
     * What the admin screen shows: the default in force, whether it is still the one from
     * configuration, and every contest that departs from it.
     */
    public record PolicyOverview(
        boolean defaultEnabled,
        /** False once an admin has overridden the deployment default at runtime. */
        boolean defaultFromConfig,
        List<ContestRule> rules
    ) {}

    public record RuleRequest(
        @NotNull Boolean enabled,
        @Size(max = 200) String note
    ) {}

    public record DefaultRequest(
        @NotNull Boolean enabled,
        @Size(max = 200) String note
    ) {}
}
