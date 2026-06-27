package com.cpintel.service;

import com.cpintel.dto.AuthDto;
import com.cpintel.dto.UserDto;
import com.cpintel.entity.RefreshToken;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.mapper.UserMapper;
import com.cpintel.repository.jpa.RefreshTokenRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserMapper userMapper;

    @Transactional
    public AuthDto.AuthResponse register(AuthDto.RegisterRequest req, HttpServletRequest httpReq) {
        if (userRepository.existsByEmail(req.getEmail()))
            throw ApiException.conflict("Email already registered");
        if (userRepository.existsByUsername(req.getUsername()))
            throw ApiException.conflict("Username already taken");

        User user = User.builder()
            .username(req.getUsername())
            .email(req.getEmail())
            .passwordHash(passwordEncoder.encode(req.getPassword()))
            .fullName(req.getFullName())
            .role("USER")
            .isActive(true)
            .isVerified(false)
            .build();

        user = userRepository.save(user);
        log.info("New user registered: {}", user.getEmail());

        return buildAuthResponse(user, httpReq);
    }

    @Transactional
    public AuthDto.AuthResponse login(AuthDto.LoginRequest req, HttpServletRequest httpReq) {
        User user = userRepository.findByEmail(req.getEmail())
            .orElseThrow(() -> ApiException.unauthorized("Invalid email or password"));

        if (!user.getIsActive())
            throw ApiException.forbidden("Account is deactivated");

        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash()))
            throw ApiException.unauthorized("Invalid email or password");

        log.info("User logged in: {}", user.getEmail());
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
        return buildAuthResponse(user, httpReq);
    }

    @Transactional
    public void logout(String accessToken, Long userId) {
        if (StringUtils.hasText(accessToken)) {
            jwtService.blacklist(accessToken);
        }
        refreshTokenRepository.revokeAllByUserId(userId);
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
