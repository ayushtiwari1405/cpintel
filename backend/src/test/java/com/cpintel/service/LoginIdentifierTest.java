package com.cpintel.service;

import com.cpintel.config.AppMetrics;
import com.cpintel.dto.AuthDto;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Signing in with an email address or a username.
 *
 * <p>People here are given a username and a first password when their account is made, and the
 * address on the account may be one they never use — so a sign-in box that insists on the
 * address asks half of them for something they do not have to hand.
 *
 * <p>The tests that matter are the negative ones. Accepting two forms of identifier must not
 * quietly widen what counts as a valid credential: a wrong password is still a refusal whichever
 * form was typed, and the refusal must not say which of the two a caller got right.
 */
class LoginIdentifierTest {

    private UserRepository users;
    private PasswordEncoder encoder;
    private AuthService service;

    private User ada;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        encoder = mock(PasswordEncoder.class);

        RateLimitService limiter = mock(RateLimitService.class);
        when(limiter.tryAcquire(any(), any(), any())).thenReturn(true);
        when(limiter.rules()).thenReturn(new RateLimitProperties());

        JwtService jwt = mock(JwtService.class);
        when(jwt.generateAccessToken(any(), any(), any())).thenReturn("access");
        when(jwt.generateRefreshToken()).thenReturn("refresh");

        service = new AuthService(users, mock(RefreshTokenRepository.class),
            mock(UnifiedScoreRepository.class), encoder, jwt, new UserMapper(),
            mock(AuditService.class), limiter, mock(AppMetrics.class));

        ada = User.builder().userId(42L).username("Ada").email("ada@example.com")
            .passwordHash("hash").role("USER").isActive(true).isVerified(false).build();

        when(users.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());
        when(users.findByUsernameIgnoreCase(any())).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase("ada@example.com")).thenReturn(Optional.of(ada));
        when(users.findByUsernameIgnoreCase("ada")).thenReturn(Optional.of(ada));
        when(encoder.matches("correct", "hash")).thenReturn(true);
    }

    private AuthDto.LoginRequest login(String identifier, String password) {
        AuthDto.LoginRequest req = new AuthDto.LoginRequest();
        req.setIdentifier(identifier);
        req.setPassword(password);
        return req;
    }

    private AuthDto.AuthResponse signIn(String identifier, String password) {
        return service.login(login(identifier, password), new MockHttpServletRequest());
    }

    @Test
    @DisplayName("an email address signs you in")
    void byEmail() {
        assertEquals("access", signIn("ada@example.com", "correct").getAccessToken());
    }

    @Test
    @DisplayName("so does a username")
    void byUsername() {
        assertEquals("access", signIn("ada", "correct").getAccessToken());
    }

    @Test
    @DisplayName("case and surrounding space are not part of either")
    void foldsCase() {
        assertDoesNotThrow(() -> signIn("  ADA@Example.com ", "correct"));
        assertDoesNotThrow(() -> signIn("ADA", "correct"));
    }

    @Test
    @DisplayName("a wrong password is still a refusal, whichever form was typed")
    void wrongPasswordRefused() {
        assertThrows(ApiException.class, () -> signIn("ada", "wrong"));
        assertThrows(ApiException.class, () -> signIn("ada@example.com", "wrong"));
    }

    /**
     * Accepting two forms must not become a way to ask "does this account exist".
     *
     * A refusal that named the identifier as the problem would make the sign-in box an
     * enumeration oracle over the roster of whoever is being examined.
     */
    @Test
    @DisplayName("the refusal does not say whether the identifier was the part that was wrong")
    void doesNotDistinguish() {
        String unknown = assertThrows(ApiException.class,
            () -> signIn("nobody", "correct")).getMessage();
        String badPassword = assertThrows(ApiException.class,
            () -> signIn("ada", "wrong")).getMessage();

        assertEquals(unknown, badPassword);
    }

    @Test
    @DisplayName("a deactivated account is refused by either form")
    void deactivated() {
        ada.setIsActive(false);

        assertThrows(ApiException.class, () -> signIn("ada", "correct"));
        assertThrows(ApiException.class, () -> signIn("ada@example.com", "correct"));
    }

    @Test
    @DisplayName("the address is tried first, so it wins if a username somehow matches one")
    void addressWins() {
        User other = User.builder().userId(99L).username("ada@example.com")
            .email("other@example.com").passwordHash("hash").role("USER").isActive(true).build();
        when(users.findByUsernameIgnoreCase("ada@example.com")).thenReturn(Optional.of(other));

        // Both columns are unique but neither excludes the other's format, so the order has to
        // be decided rather than discovered. The address is the one people type deliberately.
        assertDoesNotThrow(() -> signIn("ada@example.com", "correct"));
        verify(users).findByEmailIgnoreCase("ada@example.com");
    }
}
