package com.cpintel.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cpintel.config.AppMetrics;
import com.cpintel.dto.AuthDto;
import com.cpintel.entity.User;
import com.cpintel.mail.MailService;
import com.cpintel.mapper.UserMapper;
import com.cpintel.repository.jpa.RefreshTokenRepository;
import com.cpintel.repository.jpa.UnifiedScoreRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.repository.jpa.VerificationTokenRepository;
import com.cpintel.security.JwtService;
import com.cpintel.security.RateLimitProperties;
import com.cpintel.security.RateLimitService;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * What the sign-in, registration, password-reset and mail paths write to the application log.
 *
 * <p>These logs are kept, shipped and read far more widely than the audit table, so they say
 * which internal account an event concerns and leave out the person's email address - including
 * an address somebody merely typed into "forgot password".
 */
class PersonalDataLoggingTest {

    private static final String ADDRESS = "ada.lovelace@example.edu";

    private final List<Logger> watched = new ArrayList<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    private UserRepository users;
    private PasswordEncoder encoder;
    private AuthService auth;
    private User ada;

    @BeforeEach
    void setUp() {
        logs.start();
        for (Class<?> c : List.of(AuthService.class, PasswordService.class, MailService.class)) {
            Logger logger = (Logger) LoggerFactory.getLogger(c);
            logger.addAppender(logs);
            watched.add(logger);
        }

        users = mock(UserRepository.class);
        encoder = mock(PasswordEncoder.class);
        RateLimitService limiter = mock(RateLimitService.class);
        when(limiter.tryAcquire(any(), any(), any())).thenReturn(true);
        when(limiter.rules()).thenReturn(new RateLimitProperties());
        JwtService jwt = mock(JwtService.class);
        when(jwt.generateAccessToken(any(), any(), any(), any())).thenReturn("access");
        when(jwt.generateRefreshToken()).thenReturn("refresh");
        com.cpintel.events.ExamLockoutService lockout = mock(com.cpintel.events.ExamLockoutService.class);
        when(lockout.lockFor(any())).thenReturn(Optional.empty());

        auth = new AuthService(users, mock(RefreshTokenRepository.class), mock(UnifiedScoreRepository.class),
            encoder, jwt, new UserMapper(), mock(AuditService.class), limiter, mock(AppMetrics.class),
            mock(com.cpintel.events.ExamLoginService.class), mock(com.cpintel.events.ExamAccessService.class),
            mock(com.cpintel.repository.jpa.GroupContestRepository.class), lockout);

        ada = User.builder().userId(42L).username("ada").email(ADDRESS).fullName("Ada Lovelace")
            .passwordHash("hash").role("USER").isActive(true).isVerified(false).build();
        when(users.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());
        when(users.findByUsernameIgnoreCase(any())).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase(ADDRESS)).thenReturn(Optional.of(ada));
        when(encoder.matches("correct", "hash")).thenReturn(true);
    }

    @AfterEach
    void detach() {
        for (Logger logger : watched) logger.detachAppender(logs);
        logs.stop();
    }

    private List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** No line names the address, its local part, or the person's name. */
    private void assertNoPersonalData(String... alsoForbidden) {
        List<String> forbidden = new ArrayList<>(List.of(ADDRESS, "ada.lovelace", "Lovelace"));
        forbidden.addAll(List.of(alsoForbidden));
        for (String line : messages()) {
            for (String word : forbidden) {
                assertFalse(line.contains(word), "log line exposes \"" + word + "\": " + line);
            }
        }
    }

    @Test
    @DisplayName("signing in logs the account id, not the address")
    void signIn() {
        AuthDto.LoginRequest req = new AuthDto.LoginRequest();
        req.setIdentifier(ADDRESS);
        req.setPassword("correct");

        auth.login(req, new MockHttpServletRequest());

        assertTrue(messages().contains("User 42 logged in"), messages().toString());
        assertNoPersonalData();
    }

    @Test
    @DisplayName("registering logs the new account's id, not its address")
    void register() {
        ReflectionTestUtils.setField(auth, "registrationEnabled", true);
        when(encoder.encode(any())).thenReturn("hash");
        when(users.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setUserId(77L);
            return u;
        });
        AuthDto.RegisterRequest req = new AuthDto.RegisterRequest();
        req.setUsername("ada");
        req.setEmail(ADDRESS);
        req.setPassword("correct horse battery");
        req.setFullName("Ada Lovelace");

        auth.register(req, new MockHttpServletRequest());

        assertTrue(messages().contains("New user registered: user 77"), messages().toString());
        assertNoPersonalData();
    }

    @Test
    @DisplayName("a reset asked for an unknown address is still logged, without the address")
    void resetForUnknownAddress() {
        PasswordService passwords = new PasswordService(users, mock(VerificationTokenRepository.class),
            mock(RefreshTokenRepository.class), encoder, mock(JwtService.class), mock(AuditService.class),
            mock(MailService.class));

        passwords.requestReset("someone.else@example.org", new MockHttpServletRequest());

        assertTrue(messages().contains("Password reset asked for an address with no account"), messages().toString());
        assertNoPersonalData("someone.else@example.org", "someone.else");
    }

    @SuppressWarnings("unchecked")
    private MailService mailWith(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        MailService mail = new MailService(provider);
        ReflectionTestUtils.setField(mail, "host", "smtp.example.edu");
        ReflectionTestUtils.setField(mail, "from", "CPIntel <no-reply@cpintel.local>");
        return mail;
    }

    @Test
    @DisplayName("a sent mail is logged with the recipient masked to its first letter and domain")
    void mailSent() {
        JavaMailSender sender = mock(JavaMailSender.class);
        mailWith(sender).send(ADDRESS, "Your password was changed", "body");

        verify(sender).send(any(SimpleMailMessage.class));
        assertTrue(messages().contains("Sent \"Your password was changed\" to a***@example.edu"), messages().toString());
        assertNoPersonalData();
    }

    @Test
    @DisplayName("a failed send is logged with the recipient masked and without the body")
    void mailFailed() {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(mock(MimeMessage.class));
        doThrow(new MailSendException("connection refused")).when(sender).send(any(MimeMessage.class));

        mailWith(sender).send(ADDRESS, "Reset your password", "https://cpintel.example.edu/reset-password?token=SECRET123",
            "<a href=\"https://cpintel.example.edu/reset-password?token=SECRET123\">reset</a>");

        assertTrue(messages().stream().anyMatch(m -> m.startsWith("Could not send \"Reset your password\" to a***@example.edu")),
            messages().toString());
        assertNoPersonalData("SECRET123", "reset-password?token");
    }

    private static final String RESET_URL = "https://cpintel.example.edu/reset-password?token=SECRET123";

    /** No SMTP host; {@code logUndelivered} as an operator might have set it, or null for unset. */
    @SuppressWarnings("unchecked")
    private MailService unconfiguredMail(String logUndelivered) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        MailService mail = new MailService(provider);
        ReflectionTestUtils.setField(mail, "host", "");
        ReflectionTestUtils.setField(mail, "logUndelivered", logUndelivered);
        return mail;
    }

    @Test
    @DisplayName("with no SMTP, an undelivered reset is logged without the address, the link or the body")
    void undeliveredByDefault() {
        for (String setting : java.util.Arrays.asList(null, "false", "", "yes", "ture", "1")) {
            logs.list.clear();
            unconfiguredMail(setting).send(ADDRESS, "Reset your password", "Open " + RESET_URL, null);

            assertEquals(1, messages().size(), "setting " + setting);
            assertTrue(messages().get(0).startsWith("No SMTP configured — \"Reset your password\" to a***@example.edu was not sent"),
                "setting " + setting);
            assertNoPersonalData("SECRET123", "reset-password", "token=", "Open ");
        }
    }

    @Test
    @DisplayName("with no SMTP and CPINTEL_MAIL_LOG_UNDELIVERED=true, the whole message is logged for an operator")
    void undeliveredWhenAskedFor() {
        for (String setting : List.of("true", " TRUE ")) {
            logs.list.clear();
            unconfiguredMail(setting).send(ADDRESS, "Reset your password", "Open " + RESET_URL, null);

            String line = messages().get(0);
            // Checked piece by piece rather than printed: a failure names what is missing only.
            assertTrue(line.contains(ADDRESS), "the recipient is missing");
            assertTrue(line.contains(RESET_URL), "the reset link is missing");
            assertTrue(line.contains("Subject: Reset your password"), "the subject is missing");
        }
    }

    @Test
    @DisplayName("masking keeps the first letter and the domain, and copes with odd input")
    void masking() {
        assertEquals("a***@example.edu", MailService.masked(ADDRESS));
        assertEquals("***", MailService.masked("no-at-sign"));
        assertEquals("***", MailService.masked("@example.edu"));
        assertEquals("(none)", MailService.masked(null));
    }
}
