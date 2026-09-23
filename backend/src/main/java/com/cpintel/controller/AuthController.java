package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.dto.AuthDto;
import com.cpintel.security.JwtService;
import com.cpintel.service.AuthService;
import com.cpintel.service.PasswordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Auth endpoints")
public class AuthController {

    private final AuthService authService;
    private final PasswordService passwordService;
    private final JwtService jwtService;

    @PostMapping("/register")
    @Operation(summary = "Register a new user")
    public ResponseEntity<ApiResponse<AuthDto.AuthResponse>> register(
        @Valid @RequestBody AuthDto.RegisterRequest req,
        HttpServletRequest httpReq
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Registration successful", authService.register(req, httpReq)));
    }

    @PostMapping("/login")
    @Operation(summary = "Login")
    public ResponseEntity<ApiResponse<AuthDto.AuthResponse>> login(
        @Valid @RequestBody AuthDto.LoginRequest req,
        HttpServletRequest httpReq
    ) {
        return ResponseEntity.ok(ApiResponse.ok(authService.login(req, httpReq)));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh access token")
    public ResponseEntity<ApiResponse<AuthDto.AuthResponse>> refresh(
        @Valid @RequestBody AuthDto.RefreshRequest req,
        HttpServletRequest httpReq
    ) {
        return ResponseEntity.ok(ApiResponse.ok(authService.refresh(req, httpReq)));
    }

    @PostMapping("/logout")
    @Operation(summary = "Logout")
    public ResponseEntity<ApiResponse<Void>> logout(
        @AuthenticationPrincipal Long userId,
        HttpServletRequest request
    ) {
        String token = extractBearerToken(request);
        authService.logout(token, userId);
        return ResponseEntity.ok(ApiResponse.message("Logged out successfully"));
    }

    /**
     * Asks for a reset link.
     *
     * <p>Answers the same way whatever happened — link sent, address unknown, account
     * deactivated. This endpoint is open to the internet, and an answer that distinguished
     * those cases would be a way to test a list of addresses against the roster of whoever is
     * being examined here. The person who owns the address finds out by receiving the mail.
     */
    @PostMapping("/forgot-password")
    @Operation(summary = "Ask for a password reset link")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(
        @Valid @RequestBody AuthDto.ForgotPasswordRequest req,
        HttpServletRequest httpReq
    ) {
        passwordService.requestReset(req.getEmail(), httpReq);
        return ResponseEntity.ok(ApiResponse.message(
            "If an account uses that address, a reset link is on its way to it."));
    }

    /**
     * Spends a reset link.
     *
     * <p>Unlike the request above this answers honestly, because somebody holding a link that
     * has expired or been used needs to be told which, and a token that resolves to nothing
     * says nothing about who owns any account.
     */
    @PostMapping("/reset-password")
    @Operation(summary = "Set a new password using a reset link")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
        @Valid @RequestBody AuthDto.ResetPasswordRequest req,
        HttpServletRequest httpReq
    ) {
        passwordService.completeReset(req.getToken(), req.getNewPassword(), httpReq);
        return ResponseEntity.ok(ApiResponse.message(
            "Your password has been changed. Sign in with it — every other device has been "
            + "signed out."));
    }

    /**
     * Changes your own password while signed in.
     *
     * <p>The current password is asked for even though the caller already holds a valid token,
     * because a token can be an unlocked laptop and this check is what makes the person at the
     * keyboard the owner rather than whoever sat down after them.
     */
    @PostMapping("/change-password")
    @Operation(summary = "Change your own password")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ApiResponse<Void>> changePassword(
        @AuthenticationPrincipal Long userId,
        @Valid @RequestBody AuthDto.ChangePasswordRequest req,
        HttpServletRequest httpReq
    ) {
        passwordService.change(userId, req.getCurrentPassword(), req.getNewPassword(), httpReq);
        return ResponseEntity.ok(ApiResponse.message(
            "Your password has been changed. Every device that was signed in has been signed "
            + "out, including this one — sign in again with the new password."));
    }

    private String extractBearerToken(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) return header.substring(7);
        return null;
    }
}
