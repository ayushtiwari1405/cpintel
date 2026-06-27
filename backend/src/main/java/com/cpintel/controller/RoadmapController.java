package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.service.RoadmapService;
import com.cpintel.service.RoadmapService.RoadmapNodeView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/roadmaps")
@RequiredArgsConstructor
@Tag(name = "Roadmap")
@SecurityRequirement(name = "bearerAuth")
public class RoadmapController {

    private final RoadmapService roadmapService;

    @GetMapping("/current")
    public ResponseEntity<ApiResponse<List<RoadmapNodeView>>> getRoadmap(
        @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(roadmapService.getRoadmap(userId)));
    }

    @PostMapping("/regenerate")
    public ResponseEntity<ApiResponse<List<RoadmapNodeView>>> regenerate(
        @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(roadmapService.regenerateRoadmap(userId)));
    }

    @PatchMapping("/nodes/{nodeId}")
    public ResponseEntity<ApiResponse<RoadmapNodeView>> updateNode(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long nodeId,
        @RequestParam String status) {
        return ResponseEntity.ok(ApiResponse.ok(
            roadmapService.markNodeProgress(userId, nodeId, status)));
    }
}
