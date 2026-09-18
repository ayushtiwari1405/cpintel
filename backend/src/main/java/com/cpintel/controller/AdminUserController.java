package com.cpintel.controller;

import com.cpintel.admin.AdminDto;
import com.cpintel.admin.AdminUserService;
import com.cpintel.common.ApiResponse;
import com.cpintel.security.Roles;
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

/**
 * Account administration.
 *
 * Every write here is recorded in the audit trail against the admin who made it, because these
 * are the actions someone will need to be able to explain later.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_CONSOLE)
@Tag(name = "Admin", description = "User administration")
@SecurityRequirement(name = "bearerAuth")
public class AdminUserController {

    private final AdminUserService users;

    @GetMapping
    @Operation(summary = "Search and page through accounts")
    public ResponseEntity<ApiResponse<AdminDto.UserPage>> list(
        @RequestParam(required = false) String query,
        @RequestParam(required = false) String role,
        @RequestParam(required = false) Boolean active,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(ApiResponse.ok(users.list(query, role, active, page, size)));
    }

    @GetMapping("/{userId}")
    @Operation(summary = "One account, with its linked platforms and recent activity")
    public ResponseEntity<ApiResponse<AdminDto.UserDetail>> detail(@PathVariable Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(users.detail(userId)));
    }

    /**
     * Super admin only, and with self-registration closed this is the only way an account comes
     * into existence at all.
     */
    @PostMapping
    @PreAuthorize(Roles.HAS_SUPER)
    @Operation(summary = "Create an account (super admin only)")
    public ResponseEntity<ApiResponse<AdminDto.UserRow>> create(
        @AuthenticationPrincipal Long adminId,
        @Valid @RequestBody AdminDto.CreateUserRequest req,
        HttpServletRequest httpReq) {
        return ResponseEntity.status(201)
            .body(ApiResponse.ok(users.createUser(adminId, req, httpReq)));
    }

    /**
     * Super admin only. Assigning roles is the one action that can create more privilege than
     * the person performing it already has, so it does not belong to the tier that merely uses
     * the console.
     */
    @PutMapping("/{userId}/role")
    @PreAuthorize(Roles.HAS_SUPER)
    @Operation(summary = "Promote to admin, or demote to a regular user (super admin only)")
    public ResponseEntity<ApiResponse<AdminDto.UserRow>> changeRole(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long userId,
        @Valid @RequestBody AdminDto.RoleRequest req,
        HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(users.changeRole(adminId, userId, req, httpReq)));
    }

    @PutMapping("/{userId}/active")
    @Operation(summary = "Activate an account, or deactivate it and end its sessions")
    public ResponseEntity<ApiResponse<AdminDto.UserRow>> setActive(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long userId,
        @Valid @RequestBody AdminDto.ActiveRequest req,
        HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(users.setActive(adminId, userId, req, httpReq)));
    }

    @PostMapping("/{userId}/revoke-sessions")
    @Operation(summary = "Sign this account out everywhere without changing it")
    public ResponseEntity<ApiResponse<Void>> revokeSessions(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long userId,
        HttpServletRequest httpReq) {
        users.revokeSessions(adminId, userId, httpReq);
        return ResponseEntity.ok(ApiResponse.message("Signed out of every device"));
    }
}
