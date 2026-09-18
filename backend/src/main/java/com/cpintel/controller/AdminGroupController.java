package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.groups.GroupService;
import com.cpintel.groups.GroupsDto;
import com.cpintel.groups.RosterImportService;
import com.cpintel.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Groups, their membership, the contests laid over them, and what the desktop lock reported.
 *
 * The whole surface is admin-only. A participant's own view lives on {@link GroupController},
 * which deliberately cannot reach the conduct data at all rather than filtering it out.
 */
@RestController
@RequestMapping("/api/v1/admin/groups")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin", description = "Group contests and their standings")
@SecurityRequirement(name = "bearerAuth")
public class AdminGroupController {

    private final GroupService groups;
    private final RosterImportService rosterImport;

    @GetMapping
    @Operation(summary = "Every group")
    public ResponseEntity<ApiResponse<List<GroupsDto.GroupSummary>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(groups.list()));
    }

    @PostMapping
    @Operation(summary = "Create a group")
    public ResponseEntity<ApiResponse<GroupsDto.GroupSummary>> create(
        @AuthenticationPrincipal Long adminId,
        @Valid @RequestBody GroupsDto.GroupRequest req,
        HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(groups.create(adminId, req, httpReq)));
    }

    @GetMapping("/{groupId}")
    @Operation(summary = "One group, with its members and contests")
    public ResponseEntity<ApiResponse<GroupsDto.GroupDetail>> detail(@PathVariable Long groupId) {
        return ResponseEntity.ok(ApiResponse.ok(groups.detail(groupId)));
    }

    @PutMapping("/{groupId}")
    @Operation(summary = "Rename a group or change its description")
    public ResponseEntity<ApiResponse<GroupsDto.GroupSummary>> update(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long groupId,
        @Valid @RequestBody GroupsDto.GroupRequest req,
        HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(groups.update(adminId, groupId, req, httpReq)));
    }

    @DeleteMapping("/{groupId}")
    @Operation(summary = "Retire a group, keeping its contests and results")
    public ResponseEntity<ApiResponse<Void>> deactivate(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long groupId,
        HttpServletRequest httpReq) {
        groups.deactivate(adminId, groupId, httpReq);
        return ResponseEntity.ok(ApiResponse.message("Group retired — its results are kept"));
    }

    // ---------------------------------------------------------------- members

    @PostMapping("/{groupId}/members")
    @Operation(summary = "Add someone to the group")
    public ResponseEntity<ApiResponse<GroupsDto.Member>> addMember(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long groupId,
        @Valid @RequestBody GroupsDto.MemberRequest req,
        HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(groups.addMember(adminId, groupId, req, httpReq)));
    }

    @PostMapping("/{groupId}/members/import")
    @Operation(summary = "Add a whole roster at once, creating any missing accounts",
        description = "Accepts a pasted roster as CSV or as tab-separated text, which is what "
            + "copying a selection out of a spreadsheet produces. Send dryRun=true first: it "
            + "writes nothing and reports what each row would do, which is the only chance to "
            + "catch a mis-read column before it becomes hundreds of wrong accounts. Creating "
            + "accounts requires SUPER_ADMIN; adding people who already have one does not.")
    public ResponseEntity<ApiResponse<RosterImportService.ImportResult>> importRoster(
        @AuthenticationPrincipal Long adminId,
        Authentication authentication,
        @PathVariable Long groupId,
        @Valid @RequestBody GroupsDto.RosterImportRequest req,
        HttpServletRequest httpReq) {

        // Read from the token's authorities rather than re-reading the user row: this is the
        // same source the filter chain authorised the request against, so the two cannot
        // disagree about who the caller is.
        boolean superAdmin = authentication != null && authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(a -> ("ROLE_" + Roles.SUPER_ADMIN).equals(a));

        return ResponseEntity.ok(ApiResponse.ok(rosterImport.importRoster(
            adminId, groupId, req.text(), Boolean.TRUE.equals(req.dryRun()),
            superAdmin, httpReq)));
    }

    @PutMapping("/{groupId}/members/{userId}")
    @Operation(summary = "Set the handle this member is found under on the judge")
    public ResponseEntity<ApiResponse<GroupsDto.Member>> updateMember(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long groupId,
        @PathVariable Long userId,
        @Valid @RequestBody GroupsDto.MemberRequest req,
        HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(
            groups.updateMember(adminId, groupId, userId, req, httpReq)));
    }

    @DeleteMapping("/{groupId}/members/{userId}")
    @Operation(summary = "Remove someone from the group")
    public ResponseEntity<ApiResponse<Void>> removeMember(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long groupId,
        @PathVariable Long userId,
        HttpServletRequest httpReq) {
        groups.removeMember(adminId, groupId, userId, httpReq);
        return ResponseEntity.ok(ApiResponse.message("Removed from the group"));
    }

    // --------------------------------------------------------------- contests

    @PostMapping("/{groupId}/contests")
    @Operation(summary = "Lay a Codeforces or DOMjudge contest over this group")
    public ResponseEntity<ApiResponse<GroupsDto.ContestSummary>> addContest(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long groupId,
        @Valid @RequestBody GroupsDto.ContestRequest req,
        HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(groups.addContest(adminId, groupId, req, httpReq)));
    }

    @DeleteMapping("/contests/{contestId}")
    @Operation(summary = "Remove a contest and everything recorded about it")
    public ResponseEntity<ApiResponse<Void>> removeContest(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long contestId,
        HttpServletRequest httpReq) {
        groups.removeContest(adminId, contestId, httpReq);
        return ResponseEntity.ok(ApiResponse.message("Contest removed"));
    }

    @GetMapping("/contests/{contestId}/standings")
    @Operation(summary = "Where the group stands, with what the lock reported")
    public ResponseEntity<ApiResponse<GroupsDto.Standings>> standings(@PathVariable Long contestId) {
        return ResponseEntity.ok(ApiResponse.ok(groups.standings(contestId, true)));
    }

    @PostMapping("/contests/{contestId}/standings/refresh")
    @Operation(summary = "Rebuild the board from the judge now")
    public ResponseEntity<ApiResponse<GroupsDto.Standings>> refresh(@PathVariable Long contestId) {
        return ResponseEntity.ok(ApiResponse.ok(groups.refreshStandings(contestId)));
    }

    @GetMapping("/contests/{contestId}/violations")
    @Operation(summary = "Everything the desktop lock reported for this contest")
    public ResponseEntity<ApiResponse<GroupsDto.ViolationFeed>> violations(
        @PathVariable Long contestId,
        @RequestParam(defaultValue = "200") int limit) {
        return ResponseEntity.ok(ApiResponse.ok(groups.violations(contestId, limit)));
    }
}
