package com.cpintel.service;

import com.cpintel.common.TokenDigest;
import com.cpintel.config.AppMetrics;
import com.cpintel.dto.AuthDto;
import com.cpintel.entity.RefreshToken;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.mapper.UserMapper;
import com.cpintel.repository.jpa.RefreshTokenRepository;
import com.cpintel.repository.jpa.UnifiedScoreRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.security.JwtService;
import com.cpintel.security.RateLimitProperties;
import com.cpintel.security.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Refresh tokens: kept only as digests, rotated on every use, and a copy presented after its
 * rotation ends every session renewed from the same sign-in.
 */
class RefreshTokenRotationTest {

    private RefreshTokenRepository tokens;
    private JwtService jwt;
    private AuditService audit;
    private AuthService service;
    private User ada;

    @BeforeEach
    void setUp() {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        tokens = mock(RefreshTokenRepository.class);
        audit = mock(AuditService.class);

        RateLimitService limiter = mock(RateLimitService.class);
        when(limiter.tryAcquire(any(), any(), any())).thenReturn(true);
        when(limiter.rules()).thenReturn(new RateLimitProperties());

        jwt = mock(JwtService.class);
        when(jwt.generateAccessToken(any(), any(), any(), any())).thenReturn("access");
        when(jwt.generateRefreshToken()).thenReturn("next-raw-token");

        var lockout = mock(com.cpintel.events.ExamLockoutService.class);
        when(lockout.lockFor(any())).thenReturn(Optional.empty());

        service = new AuthService(users, tokens, mock(UnifiedScoreRepository.class), encoder,
            jwt, new UserMapper(), audit, limiter, mock(AppMetrics.class),
            mock(com.cpintel.events.ExamLoginService.class),
            mock(com.cpintel.events.ExamAccessService.class),
            mock(com.cpintel.repository.jpa.GroupContestRepository.class),
            lockout);

        ada = User.builder().userId(42L).username("ada").email("ada@example.com")
            .passwordHash("hash").role("USER").isActive(true).isVerified(false).build();
        when(users.findByEmailIgnoreCase("ada@example.com")).thenReturn(Optional.of(ada));
        when(users.findByUsernameIgnoreCase(any())).thenReturn(Optional.empty());
        when(encoder.matches("correct", "hash")).thenReturn(true);
    }

    private RefreshToken stored(String raw, boolean revoked, Instant rotatedAt) {
        RefreshToken token = RefreshToken.builder()
            .tokenId(1L).user(ada).token(TokenDigest.of(raw)).familyId("family-1")
            .expiresAt(Instant.now().plus(Duration.ofDays(7)))
            .revoked(revoked).rotatedAt(rotatedAt).build();
        when(tokens.findByToken(TokenDigest.of(raw))).thenReturn(Optional.of(token));
        return token;
    }

    private AuthDto.AuthResponse refresh(String raw) {
        AuthDto.RefreshRequest req = new AuthDto.RefreshRequest();
        req.setRefreshToken(raw);
        return service.refresh(req, new MockHttpServletRequest());
    }

    private RefreshToken lastSaved() {
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(tokens, atLeastOnce()).save(saved.capture());
        return saved.getValue();
    }

    @Test
    @DisplayName("signing in stores the token's digest, never the token")
    void signInStoresDigest() {
        AuthDto.LoginRequest req = new AuthDto.LoginRequest();
        req.setIdentifier("ada@example.com");
        req.setPassword("correct");

        AuthDto.AuthResponse response = service.login(req, new MockHttpServletRequest());

        assertEquals("next-raw-token", response.getRefreshToken());
        RefreshToken row = lastSaved();
        assertEquals(TokenDigest.of("next-raw-token"), row.getToken());
        assertNotEquals("next-raw-token", row.getToken());
        assertNotNull(row.getFamilyId());
    }

    @Test
    @DisplayName("renewing retires the token and keeps the successor in the same family")
    void rotationKeepsFamily() {
        RefreshToken old = stored("raw-1", false, null);

        AuthDto.AuthResponse response = refresh("raw-1");

        assertEquals("next-raw-token", response.getRefreshToken());
        assertTrue(old.getRevoked());
        assertNotNull(old.getRotatedAt());
        RefreshToken next = lastSaved();
        assertEquals("family-1", next.getFamilyId());
        assertEquals(TokenDigest.of("next-raw-token"), next.getToken());
        verify(tokens, never()).revokeFamily(any());
    }

    @Test
    @DisplayName("a rotated token presented again ends the whole family and its access tokens")
    void reuseEndsFamily() {
        stored("raw-1", true, Instant.now().minus(Duration.ofMinutes(5)));
        when(tokens.revokeFamily("family-1")).thenReturn(1);

        assertThrows(ApiException.class, () -> refresh("raw-1"));

        verify(tokens).revokeFamily("family-1");
        verify(jwt).revokeUserTokens(42L);
        verify(audit).record(eq(42L), eq(AuditService.REFRESH_TOKEN_REUSED), eq("USER"),
            eq("42"), any());
    }

    @Test
    @DisplayName("two tabs racing to renew are not taken for theft")
    void raceWithinGraceIsPlainRefusal() {
        stored("raw-1", true, Instant.now().minus(Duration.ofSeconds(2)));

        assertThrows(ApiException.class, () -> refresh("raw-1"));

        verify(tokens, never()).revokeFamily(any());
        verify(jwt, never()).revokeUserTokens(any());
    }

    @Test
    @DisplayName("a token revoked by signing out is refused without raising the alarm")
    void signedOutTokenIsPlainRefusal() {
        stored("raw-1", true, null);

        assertThrows(ApiException.class, () -> refresh("raw-1"));

        verify(tokens, never()).revokeFamily(any());
        verify(audit, never()).record(any(), eq(AuditService.REFRESH_TOKEN_REUSED), any(),
            any(), any());
    }

    @Test
    @DisplayName("a family that is already over is not ended again, or audited again")
    void finishedFamilyIsQuiet() {
        stored("raw-1", true, Instant.now().minus(Duration.ofMinutes(5)));
        when(tokens.revokeFamily("family-1")).thenReturn(0);

        assertThrows(ApiException.class, () -> refresh("raw-1"));

        verify(jwt, never()).revokeUserTokens(any());
        verify(audit, never()).record(any(), eq(AuditService.REFRESH_TOKEN_REUSED), any(),
            any(), any());
    }

    @Test
    @DisplayName("the raw token from the cookie is never what is looked up")
    void lookupIsByDigest() {
        when(tokens.findByToken(any())).thenReturn(Optional.empty());

        assertThrows(ApiException.class, () -> refresh("raw-1"));

        verify(tokens).findByToken(TokenDigest.of("raw-1"));
        verify(tokens, never()).findByToken("raw-1");
    }
}
