package com.cpintel.controller;

import com.cpintel.archive.ArchiveDto;
import com.cpintel.archive.SubmissionArchive;
import com.cpintel.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Reading your own previous code back, from inside the app.
 *
 * Shared by Practice and Compete rather than living under either: the need is the same on
 * both pages, and during a locked-down contest it is the only route to code the user would
 * otherwise go and read on Codeforces.
 */
@RestController
@RequestMapping("/api/v1/submissions")
@RequiredArgsConstructor
@Tag(name = "Archive", description = "Previously submitted source code")
@SecurityRequirement(name = "bearerAuth")
public class SubmissionArchiveController {

    private final SubmissionArchive archive;

    @GetMapping("/problem/{platform}/{contestId}/{index}")
    @Operation(summary = "Every attempt at one problem — CPIntel's archive, merged with "
        + "whatever the judge knows about it where the judge publishes one")
    public ResponseEntity<ApiResponse<ArchiveDto.AttemptPage>> forProblem(
        @AuthenticationPrincipal Long userId,
        @PathVariable String platform,
        @PathVariable String contestId,
        @PathVariable String index) {
        return ResponseEntity.ok(ApiResponse.ok(archive.attemptsForProblem(
            userId, platform.toUpperCase(java.util.Locale.ROOT), contestId, index)));
    }

    @GetMapping("/recent")
    @Operation(summary = "Everything archived for this user, newest first")
    public ResponseEntity<ApiResponse<List<ArchiveDto.Attempt>>> recent(
        @AuthenticationPrincipal Long userId,
        @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(ApiResponse.ok(archive.recent(userId, limit)));
    }

    @GetMapping("/{archiveId}/source")
    @Operation(summary = "An archived source. Local read — no network, works offline")
    public ResponseEntity<ApiResponse<ArchiveDto.Source>> source(
        @AuthenticationPrincipal Long userId,
        @PathVariable String archiveId) {
        return ResponseEntity.ok(ApiResponse.ok(archive.source(userId, archiveId)));
    }

    @GetMapping("/{archiveId}/tests")
    @Operation(summary = "What the judge ran, test by test. Fetched from the platform on "
        + "first ask, then archived like the source")
    public ResponseEntity<ApiResponse<ArchiveDto.TestReport>> tests(
        @AuthenticationPrincipal Long userId,
        @PathVariable String archiveId) {
        return ResponseEntity.ok(ApiResponse.ok(archive.testReport(userId, archiveId)));
    }

    @GetMapping("/codeforces/{contestId}/{submissionId}/source")
    @Operation(summary = "Source of a submission made outside CPIntel. Fetched from "
        + "Codeforces once, then archived so later reads are local")
    public ResponseEntity<ApiResponse<ArchiveDto.Source>> codeforcesSource(
        @AuthenticationPrincipal Long userId,
        @PathVariable int contestId,
        @PathVariable long submissionId) {
        return ResponseEntity.ok(ApiResponse.ok(
            archive.codeforcesSource(userId, contestId, submissionId)));
    }
}
