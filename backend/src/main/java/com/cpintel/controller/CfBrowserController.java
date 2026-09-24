package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.practice.CfBrowserService;
import com.cpintel.practice.PracticeDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Codeforces pages fetched by the user's browser, read here. See {@link CfBrowserService}.
 */
@RestController
@RequestMapping("/api/v1/cf-browser")
@RequiredArgsConstructor
@Tag(name = "Codeforces via browser",
    description = "Pages the user's browser fetched from Codeforces, read by the server")
@SecurityRequirement(name = "bearerAuth")
public class CfBrowserController {

    /** A Codeforces page is a few hundred KB; this leaves room without inviting abuse. */
    private static final int MAX_PAGE = 3 * 1024 * 1024;

    private final CfBrowserService browser;

    public record PageRequest(@NotNull @Size(max = MAX_PAGE) String html) {}

    public record StatementRequest(@NotNull Integer contestId, @NotBlank @Size(max = 8) String index,
                                   boolean contest, @NotNull @Size(max = MAX_PAGE) String html) {}

    public record PrepareRequest(@NotNull Integer contestId, @NotBlank @Size(max = 8) String index,
                                 boolean contest, @NotBlank @Size(max = 20) String languageId,
                                 @NotNull @Size(max = 65_536) String source,
                                 @NotNull @Size(max = MAX_PAGE) String html) {}

    public record CompleteRequest(@NotBlank String archiveId, boolean contest,
                                  @NotNull @Size(max = MAX_PAGE) String html) {}

    @PostMapping("/connect")
    @Operation(summary = "Link the handle signed in on the user's browser (no cookies kept)")
    public ResponseEntity<ApiResponse<PracticeDto.SessionStatus>> connect(
        @AuthenticationPrincipal Long userId, @Valid @RequestBody PageRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(browser.connect(userId, req.html())));
    }

    @PostMapping("/statement")
    @Operation(summary = "Read a statement page the browser fetched")
    public ResponseEntity<ApiResponse<PracticeDto.ProblemDetail>> statement(
        @Valid @RequestBody StatementRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
            browser.statement(req.contestId(), req.index(), req.contest(), req.html())));
    }

    @PostMapping("/languages")
    @Operation(summary = "Read the compilers off a submit page the browser fetched")
    public ResponseEntity<ApiResponse<List<PracticeDto.LanguageOption>>> languages(
        @Valid @RequestBody PageRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(browser.languages(req.html())));
    }

    @PostMapping("/submit/prepare")
    @Operation(summary = "Archive the code and return the form the browser should post")
    public ResponseEntity<ApiResponse<CfBrowserService.BrowserSubmission>> prepare(
        @AuthenticationPrincipal Long userId, @Valid @RequestBody PrepareRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(browser.prepare(userId, req.contestId(),
            req.index(), req.contest(), req.languageId(), req.source(), req.html())));
    }

    @PostMapping("/submit/complete")
    @Operation(summary = "Read the submission id off Codeforces' answer and archive it")
    public ResponseEntity<ApiResponse<Map<String, Long>>> complete(
        @AuthenticationPrincipal Long userId, @Valid @RequestBody CompleteRequest req) {
        long id = browser.complete(userId, req.archiveId(), req.contest(), req.html());
        return ResponseEntity.ok(ApiResponse.ok(Map.of("submissionId", id)));
    }
}
