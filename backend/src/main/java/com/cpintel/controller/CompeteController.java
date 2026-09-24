package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.compete.CompeteDto;
import com.cpintel.compete.CompeteService;
import com.cpintel.files.FilesDto;
import com.cpintel.practice.PracticeDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The compete arena's HTTP surface.
 *
 * <p>Every route carries the judge in the path. That is a deliberate change from the earlier
 * shape, where a bare numeric contest id was assumed to be a Codeforces one: two judges number
 * their contests independently, so {@code /compete/3} is ambiguous the moment a second judge
 * exists, and inferring the platform from the id's shape would have been a guess that broke on
 * the first DOMjudge contest someone called "2024".
 */
@RestController
@RequestMapping("/api/v1/compete")
@RequiredArgsConstructor
@Tag(name = "Compete", description = "Run a live contest — Codeforces or DOMjudge — from one page")
@SecurityRequirement(name = "bearerAuth")
public class CompeteController {

    private final CompeteService competeService;

    @PostMapping("/contest")
    @Operation(summary = "Resolve a pasted contest link into contest metadata and phase")
    public ResponseEntity<ApiResponse<CompeteDto.ContestInfo>> load(
        @AuthenticationPrincipal Long userId,
        @Valid @RequestBody CompeteDto.LoadRequest req) {
        String contestId = competeService.parseContestId(req.platform(), req.url());
        return ResponseEntity.ok(ApiResponse.ok(
            competeService.contestInfo(userId, req.platform().name(), contestId)));
    }

    @GetMapping("/{platform}/{contestId}")
    @Operation(summary = "Re-read phase, remaining time and submission availability")
    public ResponseEntity<ApiResponse<CompeteDto.ContestInfo>> contest(
        @AuthenticationPrincipal Long userId,
        @PathVariable String platform,
        @PathVariable String contestId) {
        return ResponseEntity.ok(ApiResponse.ok(
            competeService.contestInfo(userId, platform, contestId)));
    }

    @GetMapping("/{platform}/{contestId}/problems/{index}")
    @Operation(summary = "Statement for one contest problem")
    public ResponseEntity<ApiResponse<PracticeDto.ProblemDetail>> statement(
        @AuthenticationPrincipal Long userId,
        @PathVariable String platform,
        @PathVariable String contestId,
        @PathVariable String index) {
        return ResponseEntity.ok(ApiResponse.ok(
            competeService.statement(userId, platform, contestId, index)));
    }

    /**
     * The statement as a PDF, for judges that publish one.
     *
     * Proxied rather than linked. A contestant's browser holds no DOMjudge credentials, and
     * sending them to the judge's own site mid-round would take them out of the window the
     * contest is watching — which the lockdown would then report as leaving.
     */
    @GetMapping("/{platform}/{contestId}/problems/{index}/statement.pdf")
    @Operation(summary = "The statement document, proxied from the judge")
    public ResponseEntity<byte[]> statementPdf(
        @AuthenticationPrincipal Long userId,
        @PathVariable String platform,
        @PathVariable String contestId,
        @PathVariable String index) {
        CompeteDto.StatementDocument doc =
            competeService.statementDocument(userId, platform, contestId, index);

        // The judge's own type, not a guess. This route declared application/pdf for every
        // response, which was right for most DOMjudge problems and wrong for the ones whose
        // package holds a .txt — those reached the browser labelled as PDFs and rendered as an
        // empty pane, which is indistinguishable from a statement that failed to load.
        MediaType type;
        try {
            type = MediaType.parseMediaType(doc.contentType());
        } catch (Exception e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }

        return ResponseEntity.ok()
            .contentType(type)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename=\"" + index.toUpperCase() + extensionFor(type) + "\"")
            // Statements do not change mid-contest, and 200 contestants each opening four
            // problems is 800 fetches of the same handful of documents otherwise.
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=600")
            .body(doc.bytes());
    }

    /**
     * The statement's text, read out of the PDF when it is one — what the arena parses the
     * examples from. The PDF itself is still what the contestant reads.
     */
    @GetMapping(value = "/{platform}/{contestId}/problems/{index}/statement.txt",
                produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    @Operation(summary = "The statement as plain text, for reading its examples")
    public ResponseEntity<String> statementText(
        @AuthenticationPrincipal Long userId,
        @PathVariable String platform,
        @PathVariable String contestId,
        @PathVariable String index) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=600")
            .body(competeService.statementText(userId, platform, contestId, index));
    }

    /** Names the download after what it actually is, for the "open in a new tab" case. */
    private String extensionFor(MediaType type) {
        if (MediaType.APPLICATION_PDF.isCompatibleWith(type)) return ".pdf";
        if (MediaType.TEXT_HTML.isCompatibleWith(type)) return ".html";
        if (MediaType.TEXT_PLAIN.isCompatibleWith(type)) return ".txt";
        return "";
    }

    @GetMapping("/{platform}/{contestId}/languages")
    @Operation(summary = "The languages this contest accepts")
    public ResponseEntity<ApiResponse<List<PracticeDto.LanguageOption>>> languages(
        @AuthenticationPrincipal Long userId,
        @PathVariable String platform,
        @PathVariable String contestId) {
        return ResponseEntity.ok(ApiResponse.ok(
            competeService.languages(userId, platform, contestId)));
    }

    @PostMapping("/{platform}/{contestId}/submit")
    @Operation(summary = "Submit into the running contest as a contestant")
    public ResponseEntity<ApiResponse<CompeteDto.ContestSubmission>> submit(
        @AuthenticationPrincipal Long userId,
        @PathVariable String platform,
        @PathVariable String contestId,
        @Valid @RequestBody CompeteDto.ContestSubmitRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
            competeService.submit(userId, platform, contestId, req)));
    }

    @GetMapping("/{platform}/{contestId}/submissions")
    @Operation(summary = "This user's submissions in the contest, newest first")
    public ResponseEntity<ApiResponse<List<CompeteDto.ContestSubmission>>> submissions(
        @AuthenticationPrincipal Long userId,
        @PathVariable String platform,
        @PathVariable String contestId) {
        return ResponseEntity.ok(ApiResponse.ok(
            competeService.submissions(userId, platform, contestId)));
    }

    @GetMapping("/{platform}/{contestId}/files")
    @Operation(summary = "Your own uploaded files, if this contest allows them")
    public ResponseEntity<ApiResponse<FilesDto.Vault>> files(
        @AuthenticationPrincipal Long userId,
        @PathVariable String platform,
        @PathVariable String contestId) {
        return ResponseEntity.ok(ApiResponse.ok(
            competeService.files(userId, platform, contestId)));
    }

    @GetMapping("/{platform}/{contestId}/files/{fileId}")
    @Operation(summary = "Open one of your files without leaving the contest")
    public ResponseEntity<ApiResponse<FilesDto.FileContent>> file(
        @AuthenticationPrincipal Long userId,
        @PathVariable String platform,
        @PathVariable String contestId,
        @PathVariable String fileId) {
        return ResponseEntity.ok(ApiResponse.ok(
            competeService.file(userId, platform, contestId, fileId)));
    }

    @GetMapping("/{platform}/{contestId}/files/{fileId}/download")
    @Operation(summary = "The raw bytes of one of your files")
    public ResponseEntity<byte[]> downloadFile(
        @AuthenticationPrincipal Long userId,
        @PathVariable String platform,
        @PathVariable String contestId,
        @PathVariable String fileId) {
        return PersonalFileController.asDownload(
            competeService.fileDownload(userId, platform, contestId, fileId));
    }

    /**
     * The whole board, for the leaderboard panel in the arena's sidebar.
     *
     * Separate from {@code /rank} rather than folded into it. The rank is polled continuously
     * by the header for every contestant in the room; the board is read only while somebody
     * has the panel open. Serving both from one endpoint would have made the expensive one as
     * frequent as the cheap one.
     */
    @GetMapping("/{platform}/{contestId}/leaderboard")
    @Operation(summary = "The contest's full board, with this contestant's team marked")
    public ResponseEntity<ApiResponse<CompeteDto.Leaderboard>> leaderboard(
        @AuthenticationPrincipal Long userId,
        @PathVariable String platform,
        @PathVariable String contestId) {
        return ResponseEntity.ok(ApiResponse.ok(
            competeService.leaderboard(userId, platform, contestId)));
    }

    @GetMapping("/{platform}/{contestId}/rank")
    @Operation(summary = "Live rank, score and penalty for this contestant")
    public ResponseEntity<ApiResponse<CompeteDto.RankInfo>> rank(
        @AuthenticationPrincipal Long userId,
        @PathVariable String platform,
        @PathVariable String contestId) {
        return ResponseEntity.ok(ApiResponse.ok(
            competeService.rank(userId, platform, contestId)));
    }
}
