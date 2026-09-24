package com.cpintel.controller;

import com.cpintel.archive.ArchiveDto;
import com.cpintel.archive.SubmissionArchive;
import com.cpintel.common.ApiResponse;
import com.cpintel.events.LiveExamGuard;
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
    /** Scopes all of this to the paper while an examination is running. */
    private final LiveExamGuard exams;

    @GetMapping("/problem/{platform}/{contestId}/{index}")
    @Operation(summary = "Every attempt at one problem — CPIntel's archive, merged with "
        + "whatever the judge knows about it where the judge publishes one")
    public ResponseEntity<ApiResponse<ArchiveDto.AttemptPage>> forProblem(
        @AuthenticationPrincipal Long userId,
        @PathVariable String platform,
        @PathVariable String contestId,
        @PathVariable String index) {
        String judge = platform.toUpperCase(java.util.Locale.ROOT);
        exams.requireContestAllowed(userId, judge, contestId);
        ArchiveDto.AttemptPage page = archive.attemptsForProblem(userId, judge, contestId, index);
        // The paper may share its judge contest with an earlier round; only its own attempts.
        var live = exams.liveExamOn(userId, judge, contestId);
        if (live.isPresent()) {
            page = new ArchiveDto.AttemptPage(page.attempts().stream()
                .filter(a -> LiveExamGuard.madeDuring(live.get(), a.submittedAt()))
                .toList(), page.codeforcesReachable(), page.notice());
        }
        return ResponseEntity.ok(ApiResponse.ok(page));
    }

    @GetMapping("/recent")
    @Operation(summary = "Everything archived for this user, newest first")
    public ResponseEntity<ApiResponse<List<ArchiveDto.Attempt>>> recent(
        @AuthenticationPrincipal Long userId,
        @RequestParam(defaultValue = "50") int limit) {
        List<ArchiveDto.Attempt> all = archive.recent(userId, limit);
        // Mid-examination, "everything I have written" is only what was written during it.
        var live = exams.liveExamFor(userId);
        if (live.isPresent()) {
            var exam = live.get();
            all = all.stream()
                .filter(a -> exam.getPlatform().equalsIgnoreCase(a.platform())
                    && exam.getExternalId().equals(a.contestId())
                    && LiveExamGuard.madeDuring(exam, a.submittedAt()))
                .toList();
        }
        return ResponseEntity.ok(ApiResponse.ok(all));
    }

    @GetMapping("/{archiveId}/source")
    @Operation(summary = "An archived source. Local read — no network, works offline")
    public ResponseEntity<ApiResponse<ArchiveDto.Source>> source(
        @AuthenticationPrincipal Long userId,
        @PathVariable String archiveId) {
        return ResponseEntity.ok(ApiResponse.ok(scoped(userId, archiveId)));
    }

    @GetMapping("/{archiveId}/tests")
    @Operation(summary = "What the judge ran, test by test. Fetched from the platform on "
        + "first ask, then archived like the source")
    public ResponseEntity<ApiResponse<ArchiveDto.TestReport>> tests(
        @AuthenticationPrincipal Long userId,
        @PathVariable String archiveId) {
        scoped(userId, archiveId);
        return ResponseEntity.ok(ApiResponse.ok(archive.testReport(userId, archiveId)));
    }

    @GetMapping("/codeforces/{contestId}/{submissionId}/source")
    @Operation(summary = "Source of a submission made outside CPIntel. Fetched from "
        + "Codeforces once, then archived so later reads are local")
    public ResponseEntity<ApiResponse<ArchiveDto.Source>> codeforcesSource(
        @AuthenticationPrincipal Long userId,
        @PathVariable int contestId,
        @PathVariable long submissionId) {
        exams.requireContestAllowed(userId, "CODEFORCES", String.valueOf(contestId));
        return ResponseEntity.ok(ApiResponse.ok(
            archive.codeforcesSource(userId, contestId, submissionId)));
    }

    /** Reads an archived source, refusing it when a live examination puts it out of bounds. */
    private ArchiveDto.Source scoped(Long userId, String archiveId) {
        ArchiveDto.Source source = archive.source(userId, archiveId);
        exams.requireSubmissionAllowed(userId, source.platform(), source.contestId(),
            source.submittedAt());
        return source;
    }
}
