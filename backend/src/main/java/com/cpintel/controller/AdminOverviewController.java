package com.cpintel.controller;

import com.cpintel.admin.AdminAuditService;
import com.cpintel.admin.AdminDto;
import com.cpintel.admin.AdminOverviewService;
import com.cpintel.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * The console's landing screen and its audit trail.
 *
 * Both are read-only. Nothing an admin can do from here changes anything — which is worth
 * keeping true, so that opening the console to check on something is never itself an event.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin", description = "Deployment overview and audit trail")
@SecurityRequirement(name = "bearerAuth")
public class AdminOverviewController {

    private final AdminOverviewService overview;
    private final AdminAuditService audit;

    @GetMapping("/overview")
    @Operation(summary = "Account, sync, file and policy figures for the deployment")
    public ResponseEntity<ApiResponse<AdminDto.Overview>> overview() {
        return ResponseEntity.ok(ApiResponse.ok(overview.overview()));
    }

    @GetMapping("/audit")
    @Operation(summary = "The audit trail, newest first")
    public ResponseEntity<ApiResponse<AdminDto.AuditPage>> audit(
        @RequestParam(required = false) String action,
        @RequestParam(required = false) Long userId,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.ok(audit.list(action, userId, since, page, size)));
    }
}
