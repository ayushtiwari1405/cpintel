package com.cpintel.service;

import com.cpintel.dto.AuthDto;
import com.cpintel.dto.UserDto;
import com.cpintel.entity.RefreshToken;
import com.cpintel.entity.UnifiedScore;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.mapper.UserMapper;
import com.cpintel.repository.jpa.RefreshTokenRepository;
import com.cpintel.repository.jpa.UnifiedScoreRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.security.JwtService;
import com.cpintel.config.AppMetrics;
import com.cpintel.security.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import com.cpintel.security.Roles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    /** Login attempts counted against the account, not the caller's address. */
    private static final String ACCOUNT_BUCKET = "login-account";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UnifiedScoreRepository unifiedScoreRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserMapper userMapper;
    private final AuditService auditService;
    private final RateLimitService rateLimiter;
    private final AppMetrics metrics;

    /**
     * Whether anyone may create their own account.
     *
     * Off by default. With it off the only way in is a super admin creating the account, which
     * is what makes the member list of a deployment something somebody decided rather than
     * something that accumulated. The endpoint stays mapped and public so that turning
     * sign-up off produces an honest "registration is closed" answer instead of a 404 that
     * looks like the server is broken.
     */
    @Value("${cpintel.auth.registration-enabled:false}")
    private boolean registrationEnabled;

    @Transactional
    public AuthDto.AuthResponse register(AuthDto.RegisterRequest req, HttpServletRequest httpReq) {
        if (!registrationEnabled) {
            auditService.record(null, AuditService.REGISTER_BLOCKED, "EMAIL", req.getEmail(), httpReq);
            throw ApiException.forbidden(
                "Registration is closed. Ask an administrator to create your account.");
        }

        if (userRepository.existsByEmail(req.getEmail()))
            throw ApiException.conflict("Email already registered");
        if (userRepository.existsByUsername(req.getUsername()))
            throw ApiException.conflict("Username already taken");

        User user = User.builder()
            .username(req.getUsername())
            .email(req.getEmail())
            .passwordHash(passwordEncoder.encode(req.getPassword()))
            .fullName(req.getFullName())
            .role(Roles.USER)
            .isActive(true)
            .isVerified(false)
            .build();

        user = userRepository.save(user);

        // Oracle created this row from the trg_users_create_unified_score trigger.
        // Postgres could do the same, but the rest of the analytics engine now lives
        // in Java, and a row that appears from nowhere is the kind of thing that makes
        // registration hard to reason about. Created explicitly instead, in the same
        // transaction. (The roadmap skeleton, which had its own trigger, was already
        // seeded lazily by RoadmapService.ensureSeeded.)
        unifiedScoreRepository.save(UnifiedScore.builder().user(user).build());

        log.info("New user registered: {}", user.getEmail());
        auditService.record(user.getUserId(), AuditService.REGISTER, "USER",
            String.valueOf(user.getUserId()), httpReq);

        return buildAuthResponse(user, httpReq);
    }

    @Transactional
    public AuthDto.AuthResponse login(AuthDto.LoginRequest req, HttpServletRequest httpReq) {
        // Limited per account as well as per address.
        //
        // The address limit in RateLimitFilter stops one machine hammering the login, but says
        // nothing about a thousand machines each making four attempts against the same account,
        // which is the shape credential stuffing actually takes. Counting against the email
        // closes that, and the counter is cleared on success below so an honest person who
        // mistyped their password several times is not left on the edge of a lockout.
        String email = req.getEmail() == null ? "" : req.getEmail().trim().toLowerCase(Locale.ROOT);
        if (!rateLimiter.tryAcquire(ACCOUNT_BUCKET, email, rateLimiter.rules().getAccount())) {
            auditService.record(null, AuditService.LOGIN_THROTTLED, "EMAIL", email, httpReq);
            metrics.throttled(ACCOUNT_BUCKET);
            throw ApiException.tooManyRequests(
                "Too many sign-in attempts for this account. Try again in "
                + rateLimiter.retryAfterSeconds(ACCOUNT_BUCKET, email) + " seconds.");
        }

        User user = userRepository.findByEmail(req.getEmail()).orElse(null);

        // Failures are recorded against the email that was tried, not a user id, because the
        // interesting case for an admin reading this back is exactly the one where no such
        // account exists. The password is never part of the record.
        if (user == null) {
            auditService.record(null, AuditService.LOGIN_FAILED, "EMAIL", req.getEmail(), httpReq);
            throw ApiException.unauthorized("Invalid email or password");
        }

        if (!user.getIsActive()) {
            auditService.record(user.getUserId(), AuditService.LOGIN_FAILED, "USER",
                "deactivated", httpReq);
            throw ApiException.forbidden("Account is deactivated");
        }

        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            auditService.record(user.getUserId(), AuditService.LOGIN_FAILED, "USER",
                "bad-password", httpReq);
            throw ApiException.unauthorized("Invalid email or password");
        }

        rateLimiter.reset(ACCOUNT_BUCKET, email);

        log.info("User logged in: {}", user.getEmail());
        auditService.record(user.getUserId(), AuditService.LOGIN, "USER",
            String.valueOf(user.getUserId()), httpReq);
        return buildAuthResponse(user, httpReq);
    }

    @Transactional
    public AuthDto.AuthResponse refresh(AuthDto.RefreshRequest req, HttpServletRequest httpReq) {
        RefreshToken token = refreshTokenRepository.findByToken(req.getRefreshToken())
            .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token"));

        if (token.getRevoked())
            throw ApiException.unauthorized("Refresh token has been revoked");

        if (token.getExpiresAt().isBefore(Instant.now())) {
            refreshTokenRepository.delete(token);
            throw ApiException.unauthorized("Refresh token expired");
        }

        // Rotate: revoke old, issue new
        token.setRevoked(true);
        refreshTokenRepository.save(token);

        User user = token.getUser();

        // Checked here as well as at login. Refresh is the one path that hands out a fresh
        // access token without a password, so without this an account deactivated mid-session
        // would keep renewing itself for as long as its refresh token lived.
        if (!user.getIsActive())
            throw ApiException.forbidden("Account is deactivated");

        return buildAuthResponse(user, httpReq);
    }

    @Transactional
    public void logout(String accessToken, Long userId) {
        if (StringUtils.hasText(accessToken)) {
            jwtService.blacklist(accessToken);
        }
        refreshTokenRepository.revokeAllByUserId(userId);
        auditService.record(userId, AuditService.LOGOUT, "USER", String.valueOf(userId));
        log.info("User {} logged out", userId);
    }

    private AuthDto.AuthResponse buildAuthResponse(User user, HttpServletRequest req) {
        String accessToken  = jwtService.generateAccessToken(user.getUserId(), user.getEmail(), user.getRole());
        String refreshToken = jwtService.generateRefreshToken();

        RefreshToken rt = RefreshToken.builder()
            .user(user)
            .token(refreshToken)
            .expiresAt(Instant.now().plusMillis(7 * 24 * 60 * 60 * 1000L))
            .ipAddress(req.getRemoteAddr())
            .deviceInfo(req.getHeader("User-Agent"))
            .revoked(false)
            .build();
        refreshTokenRepository.save(rt);

        return AuthDto.AuthResponse.builder()
            .accessToken(accessToken)
            .refreshToken(refreshToken)
            .user(userMapper.toProfile(user))
            .build();
    }
}
