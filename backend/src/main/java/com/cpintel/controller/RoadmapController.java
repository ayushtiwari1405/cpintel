package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.roadmap.GauntletDto;
import com.cpintel.roadmap.GauntletService;
import com.cpintel.service.RoadmapService;
import com.cpintel.service.RoadmapService.RoadmapNodeView;
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
@RequestMapping("/api/v1/roadmaps")
@RequiredArgsConstructor
@Tag(name = "Roadmap")
@SecurityRequirement(name = "bearerAuth")
public class RoadmapController {

    private final RoadmapService roadmapService;
    private final GauntletService gauntletService;

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

    // ── placement gauntlet ─────────────────────────────────────────────────

    @GetMapping("/gauntlet")
    @Operation(summary = "The placement gauntlet's questions, and the last result",
        description = "Options are served shuffled and unmarked; nothing here says which is right.")
    public ResponseEntity<ApiResponse<GauntletDto.Paper>> gauntlet(
        @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(gauntletService.paper(userId)));
    }

    @PostMapping("/gauntlet/start")
    @Operation(summary = "Open an attempt; answers are recorded against it as they are checked")
    public ResponseEntity<ApiResponse<GauntletDto.Started>> startGauntlet(
        @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(gauntletService.start(userId)));
    }

    @PostMapping("/gauntlet/check")
    @Operation(summary = "Mark one tier's answers, so the page knows whether to climb",
        description = "Each question counts once per attempt, and a tier opens only after the "
            + "one below it was passed in the same attempt.")
    public ResponseEntity<ApiResponse<List<GauntletDto.Checked>>> checkGauntlet(
        @AuthenticationPrincipal Long userId,
        @Valid @RequestBody GauntletDto.CheckRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
            gauntletService.check(userId, req.attemptId(), req.answers())));
    }

    @PostMapping("/gauntlet/submit")
    @Operation(summary = "Place the user from the attempt's recorded answers and move the "
        + "roadmap to match")
    public ResponseEntity<ApiResponse<GauntletDto.ResultView>> submitGauntlet(
        @AuthenticationPrincipal Long userId,
        @Valid @RequestBody GauntletDto.SubmitRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(gauntletService.submit(userId, req.attemptId())));
    }
}
