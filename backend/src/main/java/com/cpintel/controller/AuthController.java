package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.dto.AuthDto;
import com.cpintel.exception.ApiException;
import com.cpintel.security.JwtProperties;
import com.cpintel.security.JwtService;
import com.cpintel.service.AuthService;
import com.cpintel.service.PasswordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
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
    private final JwtProperties jwtProperties;

    /**
     * The refresh token travels as an HttpOnly cookie, never in a response body.
     *
     * <p>It is the long-lived half of a session. Kept in localStorage — where it used to be — any
     * script that ever ran on the page could read it and sign in as this user from anywhere for
     * a week. As an HttpOnly cookie scoped to the auth routes, page scripts cannot see it at all;
     * only the refresh and logout requests carry it. The access token lives fifteen minutes and
     * only in memory. SameSite=Strict: no other site can make the browser send it.
     */
    static final String REFRESH_COOKIE = "cpintel_refresh";

    private ResponseEntity<ApiResponse<AuthDto.AuthResponse>> withRefreshCookie(
            HttpStatus status, String message, AuthDto.AuthResponse auth, HttpServletRequest req) {
        String token = auth.getRefreshToken();
        auth.setRefreshToken(null);
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, token == null ? "" : token)
            .httpOnly(true)
            // Behind nginx this is https (forwarded headers); plain http only in development.
            .secure(req.isSecure())
            .sameSite("Strict")
            .path("/api/v1/auth")
            .maxAge(Duration.ofMillis(jwtProperties.getRefreshExpiryMs()))
            .build();
        return ResponseEntity.status(status)
            .header(HttpHeaders.SET_COOKIE, cookie.toString())
            .body(message == null ? ApiResponse.ok(auth) : ApiResponse.ok(message, auth));
    }

    private static String clearedRefreshCookie(HttpServletRequest req) {
        return ResponseCookie.from(REFRESH_COOKIE, "").httpOnly(true).secure(req.isSecure())
            .sameSite("Strict").path("/api/v1/auth").maxAge(0).build().toString();
    }

    @PostMapping("/register")
    @Operation(summary = "Register a new user")
    public ResponseEntity<ApiResponse<AuthDto.AuthResponse>> register(
        @Valid @RequestBody AuthDto.RegisterRequest req,
        HttpServletRequest httpReq
    ) {
        return withRefreshCookie(HttpStatus.CREATED, "Registration successful",
            authService.register(req, httpReq), httpReq);
    }

    @PostMapping("/login")
    @Operation(summary = "Login")
    public ResponseEntity<ApiResponse<AuthDto.AuthResponse>> login(
        @Valid @RequestBody AuthDto.LoginRequest req,
        HttpServletRequest httpReq
    ) {
        return withRefreshCookie(HttpStatus.OK, null, authService.login(req, httpReq), httpReq);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh access token")
    public ResponseEntity<ApiResponse<AuthDto.AuthResponse>> refresh(
        @RequestBody(required = false) AuthDto.RefreshRequest req,
        @CookieValue(name = REFRESH_COOKIE, required = false) String cookie,
        HttpServletRequest httpReq
    ) {
        // The cookie is what the web app and the desktop app send. A token in the body is still
        // accepted, for scripts and API clients that hold one themselves.
        String token = req != null && StringUtils.hasText(req.getRefreshToken())
            ? req.getRefreshToken() : cookie;
        if (!StringUtils.hasText(token)) throw ApiException.unauthorized("Not signed in");
        AuthDto.RefreshRequest resolved = new AuthDto.RefreshRequest();
        resolved.setRefreshToken(token);
        return withRefreshCookie(HttpStatus.OK, null, authService.refresh(resolved, httpReq),
            httpReq);
    }

    @PostMapping("/logout")
    @Operation(summary = "Logout")
    public ResponseEntity<ApiResponse<Void>> logout(
        @AuthenticationPrincipal Long userId,
        HttpServletRequest request
    ) {
        String token = extractBearerToken(request);
        authService.logout(token, userId);
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, clearedRefreshCookie(request))
            .body(ApiResponse.message("Logged out successfully"));
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
