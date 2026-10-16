package com.cpintel.service;

import com.cpintel.common.TokenDigest;
import com.cpintel.config.AppMetrics;
import com.cpintel.dto.AuthDto;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.RefreshToken;
import com.cpintel.entity.User;
import com.cpintel.events.ExamLoginService;
import com.cpintel.exception.ApiException;
import com.cpintel.mapper.UserMapper;
import com.cpintel.repository.jpa.GroupContestRepository;
import com.cpintel.repository.jpa.RefreshTokenRepository;
import com.cpintel.repository.jpa.UnifiedScoreRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.security.DesktopClient;
import com.cpintel.security.JwtService;
import com.cpintel.security.RateLimitProperties;
import com.cpintel.security.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * An examination that requires the desktop app refuses an examination session from anywhere
 * else: at sign-in, and at every renewal, so a session carried out of the app — or a paper
 * switched to desktop-only while it runs — ends at the next one.
 */
class DesktopAppRequirementTest {

    private final ExamLoginService examLogin = mock(ExamLoginService.class);
    private final GroupContestRepository events = mock(GroupContestRepository.class);
    private final RefreshTokenRepository tokens = mock(RefreshTokenRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private AuthService service;
    private User ada;
    private GroupContest paper;

    @BeforeEach
    void setUp() {
        UserRepository users = mock(UserRepository.class);
        RateLimitService limiter = mock(RateLimitService.class);
        when(limiter.tryAcquire(any(), any(), any())).thenReturn(true);
        when(limiter.rules()).thenReturn(new RateLimitProperties());
        JwtService jwt = mock(JwtService.class);
        when(jwt.generateAccessToken(any(), any(), any(), any())).thenReturn("access");
        when(jwt.generateRefreshToken()).thenReturn("refresh");
        var lockout = mock(com.cpintel.events.ExamLockoutService.class);
        when(lockout.lockFor(any())).thenReturn(Optional.empty());

        service = new AuthService(users, tokens, mock(UnifiedScoreRepository.class),
            mock(PasswordEncoder.class), jwt, new UserMapper(), audit, limiter,
            mock(AppMetrics.class), examLogin, mock(com.cpintel.events.ExamAccessService.class),
            events, lockout);

        ada = User.builder().userId(42L).username("ada").email("ada@example.com")
            .passwordHash("hash").role("USER").isActive(true).isVerified(false).build();
        when(users.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());
        when(users.findByUsernameIgnoreCase("ada")).thenReturn(Optional.of(ada));

        paper = GroupContest.builder().contestId(7L).kind("EXAM").name("Mid-semester practical")
            .startsAt(Instant.now().minusSeconds(60)).endsAt(Instant.now().plusSeconds(3600))
            .desktopAppRequired(true).build();
        when(examLogin.match(42L, "K7FQ-M2XB")).thenReturn(Optional.of(paper));
        when(examLogin.stillOpen(eq(paper), any())).thenReturn(true);
        when(events.findById(7L)).thenReturn(Optional.of(paper));
    }

    private static MockHttpServletRequest browser() {
        return new MockHttpServletRequest();
    }

    private static MockHttpServletRequest desktop() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(DesktopClient.HEADER, "1.4.0");
        return req;
    }

    private AuthDto.AuthResponse signIn(MockHttpServletRequest from) {
        AuthDto.LoginRequest req = new AuthDto.LoginRequest();
        req.setIdentifier("ada");
        req.setPassword("K7FQ-M2XB");
        return service.login(req, from);
    }

    private AuthDto.AuthResponse renew(MockHttpServletRequest from) {
        RefreshToken token = RefreshToken.builder().tokenId(1L).user(ada)
            .token(TokenDigest.of("raw")).familyId("f").examId(7L)
            .expiresAt(Instant.now().plus(Duration.ofDays(1))).revoked(false).build();
        when(tokens.findByToken(TokenDigest.of("raw"))).thenReturn(Optional.of(token));
        AuthDto.RefreshRequest req = new AuthDto.RefreshRequest();
        req.setRefreshToken("raw");
        return service.refresh(req, from);
    }

    @Test
    @DisplayName("a browser is refused with the examination password, and told why")
    void browserSignInRefused() {
        ApiException refused = assertThrows(ApiException.class, () -> signIn(browser()));
        assertEquals(AuthService.DESKTOP_APP_REQUIRED, refused.getCode());
        assertEquals(403, refused.getStatus().value());
        assertTrue(refused.getMessage().contains("desktop app"));
        verify(audit).record(eq(42L), eq(AuditService.EXAM_SIGN_IN_NOT_DESKTOP), eq("EXAM"),
            eq("7"), any());
    }

    @Test
    @DisplayName("the desktop app signs in as before")
    void desktopSignInAllowed() {
        assertEquals("EXAM", signIn(desktop()).getMode());
    }

    @Test
    @DisplayName("a paper that does not require the app lets a browser in")
    void notRequiredAllowsBrowser() {
        paper.setDesktopAppRequired(false);
        assertEquals("EXAM", signIn(browser()).getMode());
    }

    @Test
    @DisplayName("a session renewed from a browser ends; from the app it renews")
    void renewalChecksToo() {
        ApiException refused = assertThrows(ApiException.class, () -> renew(browser()));
        assertEquals(AuthService.DESKTOP_APP_REQUIRED, refused.getCode());

        assertEquals("EXAM", renew(desktop()).getMode());
    }
}
