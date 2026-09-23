package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.integration.domjudge.DomjudgeAccountService;
import com.cpintel.integration.domjudge.DomjudgeDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * What a contestant may see of their own DOMjudge account.
 *
 * <p>Read-only by design: the write side lives on {@link AdminDomjudgeController} because a
 * contestant attaching their own account could attach a teammate's. What is left here is the
 * two questions somebody about to sit a round needs answered — <em>which team am I about to
 * submit as</em>, and <em>which contests can I enter</em>.
 *
 * <p>Deliberately not nested under {@code /compete/{platform}/{contestId}}. Both of these are
 * properties of an account rather than of a contest, and the contest list in particular has to
 * be answerable <em>before</em> a contest has been chosen — which is precisely when there is
 * no {@code contestId} to put in the path.
 */
@RestController
@RequestMapping("/api/v1/domjudge")
@RequiredArgsConstructor
@Tag(name = "Compete", description = "The DOMjudge account a contestant competes as")
@SecurityRequirement(name = "bearerAuth")
public class DomjudgeController {

    private final DomjudgeAccountService accounts;

    @GetMapping("/account")
    @Operation(summary = "The DOMjudge account attached to you, and the team it competes for")
    public ResponseEntity<ApiResponse<DomjudgeDto.AccountStatus>> account(
        @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(accounts.status(userId)));
    }

    /**
     * The contests this contestant may enter.
     *
     * Answered by the judge under their own credentials, so it is DOMjudge's own view of what
     * they are registered for rather than a roster CPIntel would have to be told about and
     * then keep in step.
     */
    @GetMapping("/contests")
    @Operation(summary = "Contests visible to your DOMjudge account, running ones first")
    public ResponseEntity<ApiResponse<List<DomjudgeDto.ContestSummary>>> contests(
        @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(accounts.contests(userId)));
    }
}
