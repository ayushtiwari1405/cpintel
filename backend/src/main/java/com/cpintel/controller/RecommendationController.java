package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.service.RecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The signed-in user's own practice plans. Nothing here takes a user id: every route reads the
 * principal, so there is no parameter through which one user could ask for another's plan.
 */
@RestController
@RequestMapping("/api/v1/recommendations")
@RequiredArgsConstructor
@Tag(name = "Recommendations")
@SecurityRequirement(name = "bearerAuth")
public class RecommendationController {

    private final RecommendationService recommendationService;

    @GetMapping("/daily")
    @Operation(summary = "Get today's problem sheet")
    public ResponseEntity<ApiResponse<RecommendationService.RecommendationPayload>> getDaily(
        @AuthenticationPrincipal Long userId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(recommendationService.getDaily(userId)));
    }

    @GetMapping("/weekly")
    @Operation(summary = "Get weekly practice plan")
    public ResponseEntity<ApiResponse<RecommendationService.RecommendationPayload>> getWeekly(
        @AuthenticationPrincipal Long userId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(recommendationService.getWeekly(userId)));
    }

    @GetMapping("/contest-prep")
    @Operation(summary = "Get preparation for the next rated Codeforces round")
    public ResponseEntity<ApiResponse<RecommendationService.RecommendationPayload>> getContestPrep(
        @AuthenticationPrincipal Long userId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(recommendationService.getContestPrep(userId)));
    }

    @PostMapping("/{type}/regenerate")
    @Operation(summary = "Replace a plan (daily, weekly or contest-prep) with a new one")
    public ResponseEntity<ApiResponse<RecommendationService.RecommendationPayload>> regenerate(
        @AuthenticationPrincipal Long userId,
        @PathVariable String type
    ) {
        return ResponseEntity.ok(ApiResponse.ok(recommendationService.regenerate(
            userId, RecommendationService.SheetType.fromPath(type))));
    }

    @GetMapping("/revision")
    @Operation(summary = "Get the revision queue: what is due, and what comes due within horizonDays")
    public ResponseEntity<ApiResponse<List<RecommendationService.RevisionItem>>> getRevision(
        @AuthenticationPrincipal Long userId,
        @RequestParam(defaultValue = "14") int horizonDays
    ) {
        return ResponseEntity.ok(ApiResponse.ok(recommendationService.getRevisionQueue(userId, horizonDays)));
    }

    @PostMapping("/revision/{revisionId}/done")
    @Operation(summary = "Mark revision item as done")
    public ResponseEntity<ApiResponse<Void>> markDone(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long revisionId
    ) {
        recommendationService.markRevisionDone(userId, revisionId);
        return ResponseEntity.ok(ApiResponse.message("Revision marked complete"));
    }
}
