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
@RequestMapping("/api/v1/roadmaps")
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

    @GetMapping("/next")
    @Operation(summary = "The few skills worth working on right now, with problems attached",
        description = "A hundred and forty nodes is a map, not a to-do list. This is the to-do "
            + "list: unlocked or in-progress nodes, weakest first. The practice workspace opens "
            + "on this when no problem has been chosen.")
    public ResponseEntity<ApiResponse<List<RoadmapNodeView>>> nextUp(
        @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(roadmapService.nextUp(userId)));
    }

    @PostMapping("/regenerate")
    public ResponseEntity<ApiResponse<List<RoadmapNodeView>>> regenerate(
        @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(roadmapService.regenerateRoadmap(userId)));
    }

    @GetMapping("/nodes/{nodeKey}")
    @Operation(summary = "One skill by its key, with its problems",
        description = "Lets the practice workspace name the skill a problem came from without "
            + "fetching the whole tree.")
    public ResponseEntity<ApiResponse<RoadmapNodeView>> node(
        @AuthenticationPrincipal Long userId,
        @PathVariable String nodeKey) {
        return ResponseEntity.ok(ApiResponse.ok(roadmapService.nodeByKey(userId, nodeKey)));
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
