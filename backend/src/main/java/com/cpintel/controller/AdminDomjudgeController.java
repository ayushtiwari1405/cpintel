package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.integration.domjudge.DomjudgeAccountService;
import com.cpintel.integration.domjudge.DomjudgeDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Attaching contestants' DOMjudge accounts, which only an admin may do.
 *
 * <p>Admin-only for the obvious reason and a less obvious one. The obvious: these are other
 * people's credentials. The less obvious: a contestant who could attach their own account
 * could attach <em>any</em> account they had the password to, including a teammate's, and
 * every submission made afterwards would be attributed to that team by the judge itself. The
 * audit trail on the judge would be truthful and useless. Keeping provisioning here means the
 * mapping from a CPIntel account to a DOMjudge team is only ever written by someone running
 * the contest.
 *
 * <p>Sits under {@code /api/v1/admin/**}, which {@code SecurityConfig} already restricts to
 * ADMIN and SUPER_ADMIN; the annotation below is belt-and-braces so this cannot land open by
 * being moved.
 */
@RestController
@RequestMapping("/api/v1/admin/domjudge")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin", description = "Attach DOMjudge accounts to CPIntel users")
@SecurityRequirement(name = "bearerAuth")
public class AdminDomjudgeController {

    private final DomjudgeAccountService accounts;

    /**
     * Attaches one contestant's DOMjudge login, verifying it against the judge first.
     *
     * The response names the resolved team on purpose. Attaching the wrong account is a
     * mistake that otherwise stays invisible until the contest is over and somebody's work is
     * on another team's board, so the admin is shown what they just wired up.
     */
    @PostMapping("/credentials")
    @Operation(summary = "Attach a DOMjudge account to a CPIntel user")
    public ResponseEntity<ApiResponse<DomjudgeDto.AccountStatus>> provision(
        @Valid @RequestBody DomjudgeDto.ProvisionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(accounts.provision(req)));
    }

    /**
     * The teams an admin may put somebody in.
     *
     * Empty is a normal answer, not an error: not every DOMjudge build will list teams to
     * every account. The screen falls back to letting the judge decide the team, which is the
     * right default anyway.
     */
    @GetMapping("/teams")
    @Operation(summary = "Teams on the DOMjudge instance, for the team picker")
    public ResponseEntity<ApiResponse<List<DomjudgeDto.TeamOption>>> teams() {
        return ResponseEntity.ok(ApiResponse.ok(accounts.teams()));
    }

    @GetMapping("/credentials/{userId}")
    @Operation(summary = "Whether a user has a DOMjudge account attached, and which team")
    public ResponseEntity<ApiResponse<DomjudgeDto.AccountStatus>> status(
        @PathVariable Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(accounts.status(userId)));
    }

    @DeleteMapping("/credentials/{userId}")
    @Operation(summary = "Detach a user's DOMjudge account")
    public ResponseEntity<ApiResponse<Void>> revoke(@PathVariable Long userId) {
        accounts.revoke(userId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
