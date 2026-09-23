package com.cpintel.service;

import com.cpintel.entity.User;
import com.cpintel.entity.VerificationToken;
import com.cpintel.exception.ApiException;
import com.cpintel.mail.MailService;
import com.cpintel.repository.jpa.RefreshTokenRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.repository.jpa.VerificationTokenRepository;
import com.cpintel.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Changing your own password.
 *
 * <p>The properties worth holding onto are the ones that stop a reset being a way in rather
 * than a way back in: the raw token exists only in the email, a link works once, and redeeming
 * one closes every session — because somebody resetting a password they believe is known to
 * somebody else has not finished until the other party is signed out.
 *
 * <p>And one that is about the endpoint rather than the account: a forgotten-password request
 * has to answer identically whether or not the address belongs to anybody. It is open to the
 * internet, and the alternative is a way to test a list of addresses against the roster of
 * whoever is being examined.
 */
class PasswordServiceTest {

    private static final Long USER_ID = 42L;

    private UserRepository users;
    private VerificationTokenRepository tokens;
    private RefreshTokenRepository refreshTokens;
    private JwtService jwt;
    private MailService mail;
    private PasswordService service;

    private User user;
    private final Map<String, VerificationToken> saved = new HashMap<>();

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        tokens = mock(VerificationTokenRepository.class);
        refreshTokens = mock(RefreshTokenRepository.class);
        jwt = mock(JwtService.class);
        mail = mock(MailService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);

        when(encoder.encode(any())).thenAnswer(c -> "hash:" + c.getArgument(0));
        when(encoder.matches(any(), any())).thenAnswer(c ->
            ("hash:" + c.getArgument(0)).equals(c.getArgument(1)));
        when(mail.baseUrl()).thenReturn("https://cpintel.example");

        service = new PasswordService(users, tokens, refreshTokens, encoder, jwt,
            mock(AuditService.class), mail);
        ReflectionTestUtils.setField(service, "resetMinutes", 60L);

        user = User.builder().userId(USER_ID).email("ada@example.com").fullName("Ada")
            .passwordHash("hash:oldpassword").isActive(true).build();
        when(users.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase("ada@example.com")).thenReturn(Optional.of(user));
        when(users.findByEmailIgnoreCase("Ada@Example.com")).thenReturn(Optional.of(user));
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(users.save(any())).thenAnswer(c -> c.getArgument(0));

        saved.clear();
        when(tokens.save(any())).thenAnswer(c -> {
            VerificationToken row = c.getArgument(0);
            saved.put(row.getToken(), row);
            return row;
        });
        when(tokens.findByToken(anyString()))
            .thenAnswer(c -> Optional.ofNullable(saved.get(c.<String>getArgument(0))));
    }

    /** The raw token out of the link that was mailed. */
    private String requestAndReadLink() {
        service.requestReset("ada@example.com", null);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq("ada@example.com"), anyString(), body.capture(), any());
        String text = body.getValue();
        int start = text.indexOf("token=") + "token=".length();
        int end = text.indexOf('\n', start);
        return text.substring(start, end < 0 ? text.length() : end).trim();
    }

    @Nested
    @DisplayName("Asking for a link")
    class Requesting {

        @Test
        @DisplayName("an unknown address is answered exactly like a known one")
        void unknownAddressIsSilent() {
            when(users.findByEmailIgnoreCase("nobody@example.com")).thenReturn(Optional.empty());

            assertDoesNotThrow(() -> service.requestReset("nobody@example.com", null));

            verifyNoInteractions(refreshTokens);
            verify(tokens, never()).save(any());
            verify(mail, never()).send(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a deactivated account is not reopened by a link")
        void deactivatedGetsNothing() {
            user.setIsActive(false);

            assertDoesNotThrow(() -> service.requestReset("ada@example.com", null));

            verify(tokens, never()).save(any());
        }

        /**
         * A read of verification_tokens must yield nothing anybody can redeem.
         *
         * The raw token lives in the email and nowhere else; what is stored is its digest.
         */
        @Test
        @DisplayName("the token in the email is not the token in the database")
        void tokenIsStoredHashed() {
            String raw = requestAndReadLink();

            assertFalse(saved.containsKey(raw),
                "the raw token must not be what the row is keyed by");
            assertEquals(1, saved.size());
            assertNotEquals(raw, saved.keySet().iterator().next());
        }

        /**
         * The address is matched the way sign-in matches it.
         *
         * Registration stores an address as it was typed and the admin console lower-cases one,
         * so both forms are really in the table. Matching exactly here while sign-in folds case
         * produced the worst possible split — an account with a capital in its address could
         * sign in and could never reset — and the enumeration-safe answer hid it, because the
         * endpoint reported success while doing nothing.
         */
        @Test
        @DisplayName("an address stored with capitals still gets a link")
        void addressCaseDoesNotMatter() {
            service.requestReset("  Ada@Example.com ", null);

            verify(tokens).save(any());
            verify(mail).send(eq("ada@example.com"), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("asking again retires the link already out there")
        void issuingRetiresTheLast() {
            service.requestReset("ada@example.com", null);

            verify(tokens, atLeastOnce()).invalidateOutstanding(USER_ID, "PASSWORD_RESET");
        }
    }

    @Nested
    @DisplayName("Spending a link")
    class Redeeming {

        @Test
        @DisplayName("the right link sets the password and ends every session")
        void resetWorks() {
            String raw = requestAndReadLink();

            service.completeReset(raw, "a-brand-new-password", null);

            assertEquals("hash:a-brand-new-password", user.getPasswordHash());
            assertNotNull(user.getPasswordChangedAt());
            // Both halves, and for the reason the reset was asked for in the first place.
            verify(refreshTokens).revokeAllByUserId(USER_ID);
            verify(jwt).revokeUserTokens(USER_ID);
        }

        @Test
        @DisplayName("a link works once")
        void singleUse() {
            String raw = requestAndReadLink();
            service.completeReset(raw, "a-brand-new-password", null);

            ApiException e = assertThrows(ApiException.class,
                () -> service.completeReset(raw, "another-password", null));
            assertTrue(e.getMessage().toLowerCase().contains("already been used"));
        }

        @Test
        @DisplayName("an expired link says so rather than failing silently")
        void expiredIsExplained() {
            String raw = requestAndReadLink();
            saved.values().iterator().next().setExpiresAt(Instant.now().minusSeconds(1));

            ApiException e = assertThrows(ApiException.class,
                () -> service.completeReset(raw, "a-brand-new-password", null));
            assertTrue(e.getMessage().toLowerCase().contains("expired"));
        }

        @Test
        @DisplayName("a token nobody issued is refused")
        void unknownTokenRefused() {
            assertThrows(ApiException.class,
                () -> service.completeReset("made-up", "a-brand-new-password", null));
            verify(users, never()).save(any());
        }

        @Test
        @DisplayName("a password below the floor is refused before anything is spent")
        void shortPasswordRefused() {
            String raw = requestAndReadLink();

            assertThrows(ApiException.class, () -> service.completeReset(raw, "short", null));
            assertEquals("hash:oldpassword", user.getPasswordHash());
        }
    }

    @Nested
    @DisplayName("Changing it while signed in")
    class Changing {

        @Test
        @DisplayName("knowing the current password is what makes it yours to change")
        void currentPasswordRequired() {
            ApiException e = assertThrows(ApiException.class,
                () -> service.change(USER_ID, "not-the-password", "a-brand-new-one", null));

            assertTrue(e.getMessage().toLowerCase().contains("current password"));
            assertEquals("hash:oldpassword", user.getPasswordHash());
            verifyNoInteractions(refreshTokens);
        }

        @Test
        @DisplayName("changing it signs every device out, including this one")
        void changeEndsSessions() {
            service.change(USER_ID, "oldpassword", "a-brand-new-one", null);

            assertEquals("hash:a-brand-new-one", user.getPasswordHash());
            verify(refreshTokens).revokeAllByUserId(USER_ID);
            verify(jwt).revokeUserTokens(USER_ID);
            // And the owner is told, which is how somebody whose account was taken over finds
            // out in seconds rather than at their next sign-in.
            verify(mail).send(eq("ada@example.com"), contains("changed"), anyString(), any());
        }

        @Test
        @DisplayName("setting the password you already have is not a change")
        void sameIsRefused() {
            assertThrows(ApiException.class,
                () -> service.change(USER_ID, "oldpassword", "oldpassword", null));
        }
    }
}
