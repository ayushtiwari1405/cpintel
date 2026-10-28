package com.cpintel.controller;

import com.cpintel.classrooms.ClassroomService;
import com.cpintel.common.ApiResponse;
import com.cpintel.entity.GroupContest;
import com.cpintel.exception.ApiException;
import com.cpintel.files.ContestFilePolicy;
import com.cpintel.files.FilesDto;
import com.cpintel.repository.jpa.GroupContestRepository;
import com.cpintel.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Admin control over whether contestants can reach their personal files.
 *
 * Deliberately a list of exceptions. The deployment default decides the normal case — it
 * ships enabled, so every contest allows personal files right now — and a rule is written
 * only for a contest that must differ, which keeps this screen short enough to audit at a
 * glance before a round starts.
 *
 * Nothing here reads anyone's files. The only thing an admin can change is whether the
 * contest page offers the vault at all.
 */
@RestController
@RequestMapping("/api/v1/admin/contest-files")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin", description = "Per-contest control of personal file access")
@SecurityRequirement(name = "bearerAuth")
public class AdminContestFileController {

    private final ContestFilePolicy policy;
    private final AuditService auditService;
    private final GroupContestRepository events;
    private final ClassroomService classrooms;

    /**
     * A Codeforces contest belongs to no classroom, so the path says nothing about whose rule
     * this is; the events run on it do. An admin changes it only when they run every one of
     * those (a DOMjudge id names its classroom, which the classroom check already enforced).
     */
    private void requireRunsEvents(Long adminId, String platform, String contestId) {
        List<Long> managed = classrooms.managedIds(adminId);
        if (managed == null || "DOMJUDGE".equals(platform)) return;
        for (GroupContest e : events.findByPlatformAndExternalId(platform, contestId)) {
            if (e.getClassroomId() == null || !managed.contains(e.getClassroomId())) {
                throw ApiException.forbidden("\"" + e.getName() + "\" runs on this contest in a "
                    + "classroom you do not run, so only its own admins can change this.");
            }
        }
    }

    /** A rule an admin may see: one for a contest none of the classrooms they do not run uses. */
    private boolean visibleTo(List<Long> managed, FilesDto.ContestRule rule) {
        if ("DOMJUDGE".equals(rule.platform())) {
            Long classroom = classroomOf(rule.platform(), rule.contestId());
            return classroom != null && managed.contains(classroom);
        }
        return events.findByPlatformAndExternalId(rule.platform(), rule.contestId()).stream()
            .allMatch(e -> e.getClassroomId() != null && managed.contains(e.getClassroomId()));
    }

    /**
     * A contest's file rule is part of the event run on it, and fixed once that has started —
     * the same as every other setting. Changing it here mid-round would take a contestant's
     * notebook away (or hand one over) under them.
     */
    private void requireNotStarted(String platform, String contestId) {
        Instant now = Instant.now();
        events.findByPlatformAndExternalId(platform, contestId).stream()
            .filter(e -> e.hasStarted(now))
            .findFirst()
            .ifPresent(e -> {
                throw ApiException.badRequest("\"" + e.getName() + "\" has started, so whether "
                    + "it allows private files is fixed.");
            });
    }

    @GetMapping
    @Operation(summary = "The default in force and every contest that departs from it")
    public ResponseEntity<ApiResponse<FilesDto.PolicyOverview>> overview(
        @AuthenticationPrincipal Long adminId) {
        FilesDto.PolicyOverview all = policy.overview();
        List<Long> managed = classrooms.managedIds(adminId);
        if (managed == null) return ResponseEntity.ok(ApiResponse.ok(all));
        return ResponseEntity.ok(ApiResponse.ok(new FilesDto.PolicyOverview(
            all.defaultEnabled(), all.defaultFromConfig(),
            all.rules().stream().filter(r -> visibleTo(managed, r)).toList())));
    }

    @PutMapping("/default")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Move the deployment-wide default without a redeploy")
    public ResponseEntity<ApiResponse<FilesDto.PolicyOverview>> setDefault(
        @AuthenticationPrincipal Long adminId,
        @Valid @RequestBody FilesDto.DefaultRequest req,
        HttpServletRequest httpReq) {
        var result = policy.setDefault(adminId, req);
        auditService.record(adminId, AuditService.FILE_POLICY_DEFAULT, "POLICY",
            "default=" + req.enabled(), httpReq);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @DeleteMapping("/default")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Fall back to the default from configuration")
    public ResponseEntity<ApiResponse<FilesDto.PolicyOverview>> clearDefault(
        @AuthenticationPrincipal Long adminId,
        HttpServletRequest httpReq) {
        var result = policy.clearDefault();
        auditService.record(adminId, AuditService.FILE_POLICY_DEFAULT, "POLICY",
            "default=from-config", httpReq);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PutMapping("/{platform}/{contestId}")
    @Operation(summary = "Turn personal files on or off for one contest")
    public ResponseEntity<ApiResponse<FilesDto.ContestRule>> setRule(
        @AuthenticationPrincipal Long adminId,
        @PathVariable String platform,
        @PathVariable String contestId,
        @Valid @RequestBody FilesDto.RuleRequest req,
        HttpServletRequest httpReq) {
        String normalised = platform.toUpperCase(Locale.ROOT);
        requireRunsEvents(adminId, normalised, contestId);
        requireNotStarted(normalised, contestId);
        var rule = policy.setRule(normalised, contestId, adminId, req);
        auditService.recordIn(classroomOf(normalised, contestId), adminId,
            AuditService.FILE_POLICY_RULE, "CONTEST",
            normalised + ":" + contestId + "=" + req.enabled(), httpReq);
        return ResponseEntity.ok(ApiResponse.ok(rule));
    }

    @DeleteMapping("/{platform}/{contestId}")
    @Operation(summary = "Drop a contest's rule so it follows the default again")
    public ResponseEntity<ApiResponse<Void>> clearRule(
        @AuthenticationPrincipal Long adminId,
        @PathVariable String platform,
        @PathVariable String contestId,
        HttpServletRequest httpReq) {
        String normalised = platform.toUpperCase(Locale.ROOT);
        requireRunsEvents(adminId, normalised, contestId);
        requireNotStarted(normalised, contestId);
        policy.clearRule(normalised, contestId);
        auditService.recordIn(classroomOf(normalised, contestId), adminId,
            AuditService.FILE_POLICY_CLEARED, "CONTEST",
            normalised + ":" + contestId, httpReq);
        return ResponseEntity.ok(ApiResponse.message("Rule cleared — this contest follows the default"));
    }

    /** A DOMjudge rule belongs to the classroom its id names; a Codeforces one to none. */
    private static Long classroomOf(String platform, String contestId) {
        if (!"DOMJUDGE".equals(platform)) return null;
        var ref = com.cpintel.integration.domjudge.JudgeContestRef.tryParse(contestId);
        return ref == null ? null : ref.classroomId();
    }
}
