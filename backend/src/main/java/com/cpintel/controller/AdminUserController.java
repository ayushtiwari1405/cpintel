package com.cpintel.controller;

import com.cpintel.admin.AdminDto;
import com.cpintel.admin.AdminUserService;
import com.cpintel.common.ApiResponse;
import com.cpintel.events.EventAnalyticsService;
import com.cpintel.events.EventsDto;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    private final EventAnalyticsService analytics;

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
     * With self-registration closed, this is the only way an account comes into existence.
     *
     * Open to both console tiers, because an admin running a contest has to be able to add the
     * people sitting it. The tier check moved inward rather than away: creating an ADMIN is
     * still super-admin-only, and {@code AdminUserService} refuses it — see there for why that
     * line is drawn around the role being created rather than around the act.
     */
    @PostMapping
    @Operation(summary = "Create an account (creating an ADMIN requires super admin)")
    public ResponseEntity<ApiResponse<AdminDto.UserRow>> create(
        @AuthenticationPrincipal Long adminId,
        Authentication authentication,
        @Valid @RequestBody AdminDto.CreateUserRequest req,
        HttpServletRequest httpReq) {
        return ResponseEntity.status(201).body(ApiResponse.ok(
            users.createUser(adminId, req, Roles.isSuperAdmin(authentication), httpReq)));
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

    /**
     * Corrects somebody's details.
     *
     * <p>Open to both tiers for an ordinary user; refused for another console account unless
     * the caller is a super admin. See {@code AdminUserService.requireMayTouch} — an admin who
     * can rewrite a peer's email can start that peer's password reset, and arrive at the same
     * escalation the role endpoint is reserved for.
     */
    @PutMapping("/{userId}")
    @Operation(summary = "Correct someone's name, email, institution or country")
    public ResponseEntity<ApiResponse<AdminDto.UserRow>> update(
        @AuthenticationPrincipal Long adminId,
        Authentication authentication,
        @PathVariable Long userId,
        @Valid @RequestBody AdminDto.UpdateUserRequest req,
        HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(users.updateUser(
            adminId, userId, req, Roles.isSuperAdmin(authentication), httpReq)));
    }

    @GetMapping("/{userId}/participation")
    @Operation(summary = "Every contest and examination this person was assigned, and how "
        + "they did in it",
        description = "Includes events they never entered, which is usually the reason an "
            + "admin opened this: somebody who did not sit an examination is invisible in any "
            + "list built from results.")
    public ResponseEntity<ApiResponse<List<EventsDto.ParticipationRow>>> participation(
        @PathVariable Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(analytics.participation(userId)));
    }

    @PutMapping("/{userId}/active")
    @Operation(summary = "Activate an account, or deactivate it and end its sessions")
    public ResponseEntity<ApiResponse<AdminDto.UserRow>> setActive(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long userId,
        Authentication authentication,
        @Valid @RequestBody AdminDto.ActiveRequest req,
        HttpServletRequest httpReq) {
        return ResponseEntity.ok(ApiResponse.ok(users.setActive(
            adminId, userId, req, Roles.isSuperAdmin(authentication), httpReq)));
    }

    @PostMapping("/{userId}/revoke-sessions")
    @Operation(summary = "Sign this account out everywhere without changing it")
    public ResponseEntity<ApiResponse<Void>> revokeSessions(
        @AuthenticationPrincipal Long adminId,
        Authentication authentication,
        @PathVariable Long userId,
        HttpServletRequest httpReq) {
        users.revokeSessions(adminId, userId, Roles.isSuperAdmin(authentication), httpReq);
        return ResponseEntity.ok(ApiResponse.message("Signed out of every device"));
    }

    /**
     * Sets a new password on somebody's account, for the person who cannot reach their own mail.
     *
     * <p>The self-service route — a link emailed to the address on the account — is the better
     * one and lives on {@code /auth/forgot-password}. This exists because it does not always
     * work, and "ask an administrator" has to lead somewhere.
     *
     * <p>The new password is in the response and is the only copy: it is stored hashed and is
     * never emailed, because a live credential sitting in a mailbox is what the reset-link
     * flow exists to avoid. Leave the body's password blank to have one generated.
     */
    @PostMapping("/{userId}/password")
    @Operation(summary = "Set a new password for this account, shown once")
    public ResponseEntity<ApiResponse<AdminDto.GeneratedPassword>> setPassword(
        @AuthenticationPrincipal Long adminId,
        Authentication authentication,
        @PathVariable Long userId,
        @Valid @RequestBody(required = false) AdminDto.SetPasswordRequest req,
        HttpServletRequest httpReq) {
        AdminDto.SetPasswordRequest body =
            req == null ? new AdminDto.SetPasswordRequest(null, null) : req;
        return ResponseEntity.ok(ApiResponse.ok(
            "Give this to them directly. It is not stored anywhere you can read it again, and "
            + "it has not been emailed.",
            users.setPassword(adminId, userId, body, Roles.isSuperAdmin(authentication), httpReq)));
    }

    /**
     * Deletes an account outright. Super admin only.
     *
     * <p>The blunt instrument, and refused for anybody who has sat an examination — their
     * session log is evidence about a paper, and deleting an account should not also be a
     * decision to destroy that. Deactivating is what "remove this person" almost always means.
     */
    @DeleteMapping("/{userId}")
    @PreAuthorize(Roles.HAS_SUPER)
    @Operation(summary = "Delete an account and everything it owns (super admin only)")
    public ResponseEntity<ApiResponse<Void>> delete(
        @AuthenticationPrincipal Long adminId,
        @PathVariable Long userId,
        HttpServletRequest httpReq) {
        users.deleteUser(adminId, userId, httpReq);
        return ResponseEntity.ok(ApiResponse.message("The account and everything it owned "
            + "have been deleted."));
    }
}
