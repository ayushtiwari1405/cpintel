package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.practice.PracticeDto;
import com.cpintel.practice.PracticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/practice")
@RequiredArgsConstructor
@Tag(name = "Practice", description = "Codeforces problem fetch and submission")
@SecurityRequirement(name = "bearerAuth")
public class PracticeController {

    private final PracticeService practiceService;

    // ------------------------------------------------------------- discovery

    @GetMapping("/problems")
    @Operation(summary = "Search the Codeforces problemset")
    public ResponseEntity<ApiResponse<List<PracticeDto.ProblemSummary>>> search(
        @RequestParam(required = false) String q,
        @RequestParam(required = false) Integer minRating,
        @RequestParam(required = false) Integer maxRating,
        @RequestParam(required = false) String tag,
        @RequestParam(defaultValue = "50") int limit) {
        int capped = Math.min(Math.max(limit, 1), 200);
        return ResponseEntity.ok(ApiResponse.ok(
            practiceService.search(q, minRating, maxRating, tag, capped)));
    }

    @GetMapping("/tags")
    public ResponseEntity<ApiResponse<List<String>>> tags() {
        return ResponseEntity.ok(ApiResponse.ok(practiceService.allTags()));
    }

    @GetMapping("/problems/{contestId}/{index}")
    @Operation(summary = "Statement, constraints and sample tests for one problem")
    public ResponseEntity<ApiResponse<PracticeDto.ProblemDetail>> problem(
        @AuthenticationPrincipal Long userId,
        @PathVariable int contestId,
        @PathVariable String index) {
        return ResponseEntity.ok(ApiResponse.ok(
            practiceService.getProblem(userId, contestId, index)));
    }

    @GetMapping("/languages")
    @Operation(summary = "Codeforces programTypeId options available to this user")
    public ResponseEntity<ApiResponse<List<PracticeDto.LanguageOption>>> languages(
        @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(practiceService.languages(userId)));
    }

    // --------------------------------------------------------------- session

    @GetMapping("/cf-session")
    public ResponseEntity<ApiResponse<PracticeDto.SessionStatus>> sessionStatus(
        @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(practiceService.sessionStatus(userId)));
    }

    @PostMapping("/cf-session")
    @Operation(summary = "Connect a Codeforces session by handing over the browser Cookie "
        + "header from a logged-in codeforces.com tab")
    public ResponseEntity<ApiResponse<PracticeDto.SessionStatus>> connectSession(
        @AuthenticationPrincipal Long userId,
        @Valid @RequestBody PracticeDto.SessionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok("Codeforces session connected",
            practiceService.connectSession(userId, req.cookieHeader(), req.expectedHandle(),
                req.userAgent())));
    }

    @DeleteMapping("/cf-session")
    public ResponseEntity<ApiResponse<Void>> disconnectSession(
        @AuthenticationPrincipal Long userId) {
        practiceService.disconnectSession(userId);
        return ResponseEntity.ok(ApiResponse.message("Codeforces session removed"));
    }

    // ------------------------------------------------------------ submitting

    @PostMapping("/submit")
    @Operation(summary = "Submit source to Codeforces using the user's connected session")
    public ResponseEntity<ApiResponse<PracticeDto.SubmitResponse>> submit(
        @AuthenticationPrincipal Long userId,
        @Valid @RequestBody PracticeDto.SubmitRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(practiceService.submit(userId, req)));
    }

    @GetMapping("/submissions/{submissionId}/verdict")
    public ResponseEntity<ApiResponse<PracticeDto.VerdictResponse>> verdict(
        @AuthenticationPrincipal Long userId,
        @PathVariable long submissionId) {
        return ResponseEntity.ok(ApiResponse.ok(practiceService.verdict(userId, submissionId)));
    }
}
