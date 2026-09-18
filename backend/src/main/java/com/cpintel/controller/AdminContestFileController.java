package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.files.ContestFilePolicy;
import com.cpintel.files.FilesDto;
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

    @GetMapping
    @Operation(summary = "The default in force and every contest that departs from it")
    public ResponseEntity<ApiResponse<FilesDto.PolicyOverview>> overview() {
        return ResponseEntity.ok(ApiResponse.ok(policy.overview()));
    }

    @PutMapping("/default")
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
        var rule = policy.setRule(normalised, contestId, adminId, req);
        auditService.record(adminId, AuditService.FILE_POLICY_RULE, "CONTEST",
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
        policy.clearRule(normalised, contestId);
        auditService.record(adminId, AuditService.FILE_POLICY_CLEARED, "CONTEST",
            normalised + ":" + contestId, httpReq);
        return ResponseEntity.ok(ApiResponse.message("Rule cleared — this contest follows the default"));
    }
}
