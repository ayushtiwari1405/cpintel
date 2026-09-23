package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.groups.GroupService;
import com.cpintel.groups.GroupsDto;
import com.cpintel.groups.ContestMonitorRegistry;
import com.cpintel.groups.ViolationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * A participant's own view of the groups they are in.
 *
 * Deliberately narrow. Someone can see which contests they are enrolled in, where they placed,
 * and that the desktop lock is required — and nothing at all about anyone else's conduct. The
 * admin surface is a separate controller rather than the same one with a role check inside,
 * so there is no shared code path where a filter could be forgotten.
 */
@RestController
@RequestMapping("/api/v1/groups")
@RequiredArgsConstructor
@Tag(name = "Groups", description = "Group contests you are part of")
@SecurityRequirement(name = "bearerAuth")
public class GroupController {

    private final GroupService groups;
    private final ViolationService violations;
    private final ContestMonitorRegistry monitors;

    @GetMapping
    @Operation(summary = "The groups you belong to")
    public ResponseEntity<ApiResponse<List<GroupsDto.GroupSummary>>> myGroups(
        @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(groups.myGroups(userId)));
    }

    @GetMapping("/contests")
    @Operation(summary = "The group contests you are enrolled in, with your own placing")
    public ResponseEntity<ApiResponse<List<GroupsDto.MyContest>>> myContests(
        @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(groups.myContests(userId)));
    }

    /**
     * Whether the contest currently open on the compete page is being run for a group.
     *
     * Returns null when it is not, which is the normal case. The compete page has no idea a
     * group was laid over the Codeforces round it loaded, so this is how the desktop lock
     * learns whether there is anywhere to report.
     */
    @GetMapping("/contests/active")
    @Operation(summary = "The group contest matching an external contest, if you are in one")
    public ResponseEntity<ApiResponse<GroupsDto.ContestSummary>> active(
        @AuthenticationPrincipal Long userId,
        @RequestParam String platform,
        @RequestParam String externalId) {
        return ResponseEntity.ok(ApiResponse.ok(groups.activeFor(userId, platform, externalId)));
    }

    /**
     * Says that this contestant's monitoring is still running.
     *
     * Separate from the violation report, which is a batched evidence trail and cannot answer
     * "is the lock alive now" — the absence of violations is what a contestant who is behaving
     * and a contestant who closed the monitor have in common. Membership is checked the same
     * way violations check it, so nobody can keep somebody else's contest window looking alive.
     *
     * The reply carries the interval the client should use, so the timer and the server's
     * tolerance cannot drift apart across a release.
     */
    @PostMapping("/contests/{contestId}/monitor/heartbeat")
    @Operation(summary = "Report that your contest monitoring is still running")
    public ResponseEntity<ApiResponse<Map<String, Long>>> heartbeat(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long contestId) {
        violations.requireParticipant(userId, contestId);
        monitors.beat(userId, contestId);
        return ResponseEntity.ok(ApiResponse.ok(
            Map.of("intervalSeconds", monitors.intervalSeconds())));
    }

    @PostMapping("/contests/{contestId}/violations")
    @Operation(summary = "Report what your own desktop lock observed")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> report(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long contestId,
        @Valid @RequestBody GroupsDto.ViolationReport report) {
        int stored = violations.report(userId, contestId, report);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("stored", stored)));
    }
}
