package com.cpintel.controller;

import com.cpintel.analytics.AnalyticsService;
import com.cpintel.common.ApiResponse;
import com.cpintel.dto.analytics.AnalyticsDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics", description = "Analytics and performance data")
@SecurityRequirement(name = "bearerAuth")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/overview")
    @Operation(summary = "Get analytics overview")
    public ResponseEntity<ApiResponse<AnalyticsDto.Overview>> getOverview(
        @AuthenticationPrincipal Long userId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(analyticsService.getOverview(userId)));
    }

    @GetMapping("/topics")
    @Operation(summary = "Get topic mastery breakdown")
    public ResponseEntity<ApiResponse<List<AnalyticsDto.TopicSummary>>> getTopics(
        @AuthenticationPrincipal Long userId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(analyticsService.getTopicAnalytics(userId)));
    }

    @GetMapping("/contests")
    @Operation(summary = "Get contest analytics")
    public ResponseEntity<ApiResponse<AnalyticsDto.ContestAnalytics>> getContests(
        @AuthenticationPrincipal Long userId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(analyticsService.getContestAnalytics(userId)));
    }

    @GetMapping("/nodes")
    @Operation(summary = "Mastery for every skill-tree node the user has evidence for")
    public ResponseEntity<ApiResponse<List<AnalyticsDto.TopicSummary>>> nodes(
        @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(analyticsService.getNodeAnalytics(userId)));
    }

    @GetMapping("/trends")
    @Operation(summary = "Get activity and trend data")
    public ResponseEntity<ApiResponse<AnalyticsDto.TrendData>> getTrends(
        @AuthenticationPrincipal Long userId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(analyticsService.getTrends(userId)));
    }

    @GetMapping("/skill-basis")
    @Operation(summary = "What skill ratings are based on, and what they could be based on")
    public ResponseEntity<ApiResponse<AnalyticsDto.SkillBasis>> skillBasis(
        @AuthenticationPrincipal Long userId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(analyticsService.getSkillBasis(userId)));
    }

    /** Body: {@code {"basis": "AUTO" | "CODEFORCES" | "GAUNTLET"}}. Rebuilds the ratings on it. */
    @PutMapping("/skill-basis")
    @Operation(summary = "Choose what skill ratings are based on")
    public ResponseEntity<ApiResponse<AnalyticsDto.SkillBasis>> setSkillBasis(
        @AuthenticationPrincipal Long userId,
        @RequestBody java.util.Map<String, String> body
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
            analyticsService.setSkillBasis(userId, body.get("basis"))));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Trigger analytics recompute")
    public ResponseEntity<ApiResponse<Void>> refresh(@AuthenticationPrincipal Long userId) {
        analyticsService.triggerAnalyticsRefresh(userId);
        return ResponseEntity.ok(ApiResponse.message("Analytics refresh triggered"));
    }
}
