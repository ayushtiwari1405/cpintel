package com.cpintel.admin;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cpintel.entity.User;
import com.cpintel.repository.jpa.UnifiedScoreRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.service.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * What AdminBootstrap writes to the log on each of its paths: the account by user id, or the
 * configured address masked to its first letter and domain - never the address itself. Read from
 * the real log output, at DEBUG, so the quiet "already a super admin" line is checked too.
 */
class AdminBootstrapLoggingTest {

    private static final String ADDRESS = "grace.hopper@college.example.edu";

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger logger;
    private Level previousLevel;

    private UserRepository users;
    private AuditService audit;
    private AdminBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(AdminBootstrap.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logs.start();
        logger.addAppender(logs);

        users = mock(UserRepository.class);
        audit = mock(AuditService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("hash");
        bootstrap = new AdminBootstrap(users, mock(UnifiedScoreRepository.class), encoder, audit);
        ReflectionTestUtils.setField(bootstrap, "bootstrapEmail", "  Grace.Hopper@College.Example.edu ");
        ReflectionTestUtils.setField(bootstrap, "bootstrapPassword", "a-long-enough-password");
        ReflectionTestUtils.setField(bootstrap, "bootstrapUsername", "superadmin");
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
        logger.setLevel(previousLevel);
        logs.stop();
    }

    private List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** Something was logged, and no line has the address or its local part. */
    private void assertNoAddress() {
        assertFalse(messages().isEmpty(), "nothing was logged");
        for (String line : messages()) {
            assertFalse(line.toLowerCase().contains("grace.hopper"), "a log line names the address: " + line);
        }
    }

    private static User user(long id, String role) {
        User u = User.builder().username("someone").email(ADDRESS).role(role).isActive(false).build();
        u.setUserId(id);
        return u;
    }

    @Test
    @DisplayName("creating the super admin logs its user id")
    void created() {
        when(users.findByEmail(ADDRESS)).thenReturn(Optional.empty());
        when(users.save(any(User.class))).thenAnswer(i -> {
            User u = i.getArgument(0);
            u.setUserId(17L);
            return u;
        });

        bootstrap.run(null);

        assertNoAddress();
        assertTrue(messages().stream().anyMatch(m -> m.startsWith("Created super admin user 17 (username 'superadmin')")),
            messages().toString());
        verify(audit).record(eq(17L), eq(AuditService.ADMIN_BOOTSTRAPPED), eq("USER"), eq("17:created"));
    }

    @Test
    @DisplayName("promoting an existing account logs its user id")
    void promoted() {
        User verified = user(23L, "USER");
        verified.setIsVerified(true);
        when(users.findByEmail(ADDRESS)).thenReturn(Optional.of(verified));

        bootstrap.run(null);

        assertNoAddress();
        assertTrue(messages().contains("Promoted user 23 from USER to SUPER_ADMIN via cpintel.admin.bootstrap-email"),
            messages().toString());
    }

    @Test
    @DisplayName("an account staff made on the configured address is not promoted")
    void staffMadeAccountNotPromoted() {
        // A classroom admin created it on the address after its owner moved theirs.
        User planted = user(31L, "USER");
        planted.setIsVerified(true);
        planted.setManagedBy(2L);
        when(users.findByEmail(ADDRESS)).thenReturn(Optional.of(planted));

        bootstrap.run(null);

        assertEquals("USER", planted.getRole());
        verify(users, never()).save(any(User.class));
        verify(audit, never()).record(any(), eq(AuditService.ADMIN_BOOTSTRAPPED), any(), any());
    }

    @Test
    @DisplayName("an unverified account on the configured address is not promoted")
    void unverifiedAccountNotPromoted() {
        when(users.findByEmail(ADDRESS)).thenReturn(Optional.of(user(32L, "USER")));

        bootstrap.run(null);

        verify(users, never()).save(any(User.class));
    }

    @Test
    @DisplayName("an admin-level account is still promoted, so the setting stays a way back in")
    void adminStillPromoted() {
        when(users.findByEmail(ADDRESS)).thenReturn(Optional.of(user(33L, "ADMIN")));

        bootstrap.run(null);

        verify(users).save(any(User.class));
    }

    @Test
    @DisplayName("an account that is already a super admin is named by user id, at debug")
    void alreadySuperAdmin() {
        when(users.findByEmail(ADDRESS)).thenReturn(Optional.of(user(5L, "SUPER_ADMIN")));

        bootstrap.run(null);

        assertNoAddress();
        assertEquals(List.of("Bootstrap account (user 5) is already a super admin"), messages());
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("with no account and no password, the warning masks the address")
    void noPassword() {
        ReflectionTestUtils.setField(bootstrap, "bootstrapPassword", "");
        when(users.findByEmail(ADDRESS)).thenReturn(Optional.empty());

        bootstrap.run(null);

        assertNoAddress();
        assertTrue(messages().get(0).startsWith("cpintel.admin.bootstrap-email is set to g***@college.example.edu,"),
            messages().toString());
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("a password too short, and a username taken, are refused with the address masked")
    void refused() {
        when(users.findByEmail(ADDRESS)).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(bootstrap, "bootstrapPassword", "short");
        bootstrap.run(null);

        ReflectionTestUtils.setField(bootstrap, "bootstrapPassword", "a-long-enough-password");
        when(users.existsByUsername("superadmin")).thenReturn(true);
        bootstrap.run(null);

        assertNoAddress();
        assertTrue(messages().get(0).endsWith("Refusing to create the super admin g***@college.example.edu."), messages().toString());
        assertTrue(messages().get(1).startsWith("Cannot create super admin g***@college.example.edu: the username 'superadmin' is taken."),
            messages().toString());
        verify(users, never()).save(any());
    }

    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
