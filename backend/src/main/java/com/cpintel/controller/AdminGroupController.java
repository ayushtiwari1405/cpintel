package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.groups.GroupService;
import com.cpintel.groups.GroupsDto;
import com.cpintel.events.EventAnalyticsService;
import com.cpintel.events.EventsDto;
import com.cpintel.groups.RosterImportService;
import com.cpintel.groups.DomjudgePasswordImportService;
import com.cpintel.integration.domjudge.DomjudgeDto;
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
    private final DomjudgePasswordImportService domjudgePasswords;
    private final EventAnalyticsService analyticsService;

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
            + "catch a mis-read column before it becomes hundreds of wrong accounts. Accounts "
            + "created this way are always ordinary users. Send teamName to put the whole "
            + "import on one team; a team column in the paste still wins per row.")
    public ResponseEntity<ApiResponse<RosterImportService.ImportResult>> importRoster(
        @AuthenticationPrincipal Long adminId,
        Authentication authentication,
        @PathVariable Long groupId,
        @Valid @RequestBody GroupsDto.RosterImportRequest req,
        HttpServletRequest httpReq) {

        return ResponseEntity.ok(ApiResponse.ok(rosterImport.importRoster(
            adminId, groupId, req.text(), Boolean.TRUE.equals(req.dryRun()),
            Roles.isSuperAdmin(authentication), req.teamName(), httpReq)));
    }

    @PostMapping("/{groupId}/members/domjudge-passwords")
    @Operation(summary = "Update the DOMjudge passwords of many members at once",
        description = "Accepts djUsername,djPassword rows as CSV or tab-separated text. Each "
            + "row replaces the stored password of the group member that login is attached to, "
            + "after the judge accepts it; a member with nothing attached whose username is the "
            + "login has it attached. Send dryRun=true first to verify every row and write "
            + "nothing.")
    public ResponseEntity<ApiResponse<DomjudgePasswordImportService.Result>> domjudgePasswords(
        @PathVariable Long groupId,
        @Valid @RequestBody DomjudgeDto.BulkPasswordRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(domjudgePasswords.update(
            groupId, req.text(), Boolean.TRUE.equals(req.dryRun()))));
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

    @PostMapping("/{groupId}/members/{userId}/move")
    @Operation(summary = "Move someone to another team, keeping their judge handle",
        description = "One call rather than a remove and an add: between those two the person "
            + "is on no team, which is when an examination assigned to their old team stops "
            + "reaching them and the new one has not started to.")
    public ResponseEntity<ApiResponse<GroupsDto.Member>> moveMember(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long groupId,
        @PathVariable Long userId,
        @Valid @RequestBody GroupsDto.MoveMemberRequest req,
        HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(
            groups.moveMember(adminId, groupId, userId, req.targetGroupId(), httpReq)));
    }

    @GetMapping("/{groupId}/analytics")
    @Operation(summary = "How this team has done across everything it was assigned")
    public ResponseEntity<ApiResponse<EventsDto.TeamAnalytics>> analytics(
        @PathVariable Long groupId) {
        return ResponseEntity.ok(ApiResponse.ok(analyticsService.team(groupId)));
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
