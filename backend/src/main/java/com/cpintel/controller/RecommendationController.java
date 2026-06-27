package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.entity.RevisionSchedule;
import com.cpintel.service.RecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/recommendations")
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

    @GetMapping("/revision")
    @Operation(summary = "Get revision queue")
    public ResponseEntity<ApiResponse<List<RevisionSchedule>>> getRevision(
        @AuthenticationPrincipal Long userId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(recommendationService.getRevisionQueue(userId)));
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
