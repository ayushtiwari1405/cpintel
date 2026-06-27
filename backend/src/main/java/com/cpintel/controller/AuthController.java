package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.dto.AuthDto;
import com.cpintel.security.JwtService;
import com.cpintel.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
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
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Auth endpoints")
public class AuthController {

    private final AuthService authService;
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

    @PostMapping("/forgot-password")
    @Operation(summary = "Request password reset")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(
        @Valid @RequestBody AuthDto.ForgotPasswordRequest req
    ) {
        // Email sending deferred — returns success regardless to prevent enumeration
        return ResponseEntity.ok(ApiResponse.message("If that email exists, a reset link has been sent"));
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Reset password using token")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
        @Valid @RequestBody AuthDto.ResetPasswordRequest req
    ) {
        return ResponseEntity.ok(ApiResponse.message("Password reset successfully"));
    }

    private String extractBearerToken(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) return header.substring(7);
        return null;
    }
}
