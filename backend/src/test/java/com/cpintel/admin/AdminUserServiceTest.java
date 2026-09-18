package com.cpintel.admin;

import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.mapper.UserMapper;
import com.cpintel.repository.jpa.AuditLogRepository;
import com.cpintel.repository.jpa.RefreshTokenRepository;
import com.cpintel.repository.jpa.UnifiedScoreRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.repository.mongo.PersonalFileRepository;
import com.cpintel.security.JwtService;
import com.cpintel.service.AuditService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The rules that stop the console being used to break itself.
 *
 * Almost everything here is about lockout. An admin console whose last administrator has been
 * demoted, deactivated, or has done either to themselves is not recoverable from inside the
 * product — so those cases are refused before they happen rather than repaired afterwards.
 */
class AdminUserServiceTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long OTHER_ID = 2L;

    private UserRepository users;
    private AuditLogRepository auditLog;
    private RefreshTokenRepository refreshTokens;
    private PersonalFileRepository files;
    private AuditService audit;
    private JwtService jwt;
    private UnifiedScoreRepository unifiedScores;
    private PasswordEncoder passwordEncoder;
    private AdminUserService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        auditLog = mock(AuditLogRepository.class);
        refreshTokens = mock(RefreshTokenRepository.class);
        files = mock(PersonalFileRepository.class);
        audit = mock(AuditService.class);
        jwt = mock(JwtService.class);
        unifiedScores = mock(UnifiedScoreRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        service = new AdminUserService(users, auditLog, refreshTokens, files, unifiedScores,
            passwordEncoder, new UserMapper(), audit, jwt);

        when(passwordEncoder.encode(any())).thenReturn("hashed");

        when(auditLog.lastLoginFor(anyCollection())).thenReturn(List.of());
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        // Unless a test says otherwise, there is another admin around, so the "last admin"
        // guard is not what any of these are tripping over.
        when(users.countOtherActiveAdmins(anyLong())).thenReturn(1L);
    }

    private User user(Long id, String role, boolean active) {
        User u = User.builder()
            .userId(id).username("user" + id).email("user" + id + "@example.com")
            .role(role).isActive(active).isVerified(true)
            .build();
        when(users.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    private AdminDto.RoleRequest role(String value) {
        return new AdminDto.RoleRequest(value);
    }

    private AdminDto.ActiveRequest active(boolean value) {
        return new AdminDto.ActiveRequest(value, null);
    }

    @Nested
    @DisplayName("Roles")
    class Roles {

        @Test
        @DisplayName("a super admin cannot be demoted, so the top of the tree is never removable from inside")
        void cannotDemoteSuperAdmin() {
            user(OTHER_ID, "SUPER_ADMIN", true);

            ApiException e = assertThrows(ApiException.class,
                () -> service.changeRole(ADMIN_ID, OTHER_ID, role("USER"), null));

            assertTrue(e.getMessage().contains("super admin"));
            verify(users, never()).save(any());
        }

        @Test
        @DisplayName("SUPER_ADMIN is not assignable through the console")
        void cannotAssignSuperAdmin() {
            user(OTHER_ID, "USER", true);

            ApiException e = assertThrows(ApiException.class,
                () -> service.changeRole(ADMIN_ID, OTHER_ID, role("SUPER_ADMIN"), null));

            assertTrue(e.getMessage().contains("USER or ADMIN"));
            verify(users, never()).save(any());
        }

        @Test
        @DisplayName("promoting a user to admin takes effect on their next request, not in fifteen minutes")
        void promoteRevokesTokens() {
            User target = user(OTHER_ID, "USER", true);

            var row = service.changeRole(ADMIN_ID, OTHER_ID, role("admin"), null);

            assertEquals("ADMIN", target.getRole());
            assertEquals("ADMIN", row.role());
            verify(jwt).revokeUserTokens(OTHER_ID);
            verify(audit).record(eq(ADMIN_ID), eq(AuditService.ROLE_CHANGED), eq("USER"),
                contains("USER->ADMIN"), any());
        }

        @Test
        @DisplayName("an admin cannot change their own role")
        void cannotChangeOwnRole() {
            user(ADMIN_ID, "ADMIN", true);

            var e = assertThrows(ApiException.class,
                () -> service.changeRole(ADMIN_ID, ADMIN_ID, role("USER"), null));

            assertTrue(e.getMessage().contains("your own role"));
            verify(users, never()).save(any());
        }

        @Test
        @DisplayName("the last remaining admin cannot be demoted")
        void cannotDemoteLastAdmin() {
            user(OTHER_ID, "ADMIN", true);
            when(users.countOtherActiveAdmins(OTHER_ID)).thenReturn(0L);

            var e = assertThrows(ApiException.class,
                () -> service.changeRole(ADMIN_ID, OTHER_ID, role("USER"), null));

            assertTrue(e.getMessage().contains("only active admin"));
            verify(users, never()).save(any());
        }

        @Test
        @DisplayName("an unrecognised role is refused rather than stored")
        void rejectsUnknownRole() {
            user(OTHER_ID, "USER", true);

            var e = assertThrows(ApiException.class,
                () -> service.changeRole(ADMIN_ID, OTHER_ID, role("SUPERUSER"), null));

            assertTrue(e.getMessage().contains("USER or ADMIN"));
            verify(users, never()).save(any());
        }

        @Test
        @DisplayName("setting the role someone already has changes nothing")
        void noOpRoleChange() {
            user(OTHER_ID, "USER", true);

            service.changeRole(ADMIN_ID, OTHER_ID, role("USER"), null);

            verify(users, never()).save(any());
            verify(jwt, never()).revokeUserTokens(any());
            verify(audit, never()).record(any(), eq(AuditService.ROLE_CHANGED), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Deactivation")
    class Deactivation {

        @Test
        @DisplayName("deactivating ends the sessions the account already has")
        void deactivateEndsSessions() {
            User target = user(OTHER_ID, "USER", true);

            var row = service.setActive(ADMIN_ID, OTHER_ID, active(false), null);

            assertFalse(target.getIsActive());
            assertFalse(row.active());
            // Both halves: the refresh token stops anything new being minted, and the token
            // revocation retires the one already sitting in their browser.
            verify(refreshTokens).revokeAllByUserId(OTHER_ID);
            verify(jwt).revokeUserTokens(OTHER_ID);
            verify(audit).record(eq(ADMIN_ID), eq(AuditService.USER_DEACTIVATED), any(), any(), any());
        }

        @Test
        @DisplayName("reactivating does not sign anyone out")
        void reactivateLeavesSessionsAlone() {
            user(OTHER_ID, "USER", false);

            service.setActive(ADMIN_ID, OTHER_ID, active(true), null);

            verify(refreshTokens, never()).revokeAllByUserId(any());
            verify(audit).record(eq(ADMIN_ID), eq(AuditService.USER_ACTIVATED), any(), any(), any());
        }

        @Test
        @DisplayName("an admin cannot deactivate their own account")
        void cannotDeactivateSelf() {
            user(ADMIN_ID, "ADMIN", true);

            var e = assertThrows(ApiException.class,
                () -> service.setActive(ADMIN_ID, ADMIN_ID, active(false), null));

            assertTrue(e.getMessage().contains("your own account"));
            verify(users, never()).save(any());
        }

        @Test
        @DisplayName("the last remaining admin cannot be deactivated either")
        void cannotDeactivateLastAdmin() {
            user(OTHER_ID, "ADMIN", true);
            when(users.countOtherActiveAdmins(OTHER_ID)).thenReturn(0L);

            assertThrows(ApiException.class,
                () -> service.setActive(ADMIN_ID, OTHER_ID, active(false), null));

            verify(users, never()).save(any());
            verify(refreshTokens, never()).revokeAllByUserId(any());
        }

        @Test
        @DisplayName("deactivating an account that is already off is not a second event")
        void noOpDeactivation() {
            user(OTHER_ID, "USER", false);

            service.setActive(ADMIN_ID, OTHER_ID, active(false), null);

            verify(users, never()).save(any());
            verify(refreshTokens, never()).revokeAllByUserId(any());
        }
    }

    @Nested
    @DisplayName("Sessions")
    class Sessions {

        @Test
        @DisplayName("revoking sessions signs the account out without deactivating it")
        void revokeLeavesAccountUsable() {
            User target = user(OTHER_ID, "USER", true);

            service.revokeSessions(ADMIN_ID, OTHER_ID, null);

            assertTrue(target.getIsActive());
            verify(refreshTokens).revokeAllByUserId(OTHER_ID);
            verify(jwt).revokeUserTokens(OTHER_ID);
            verify(audit).record(eq(ADMIN_ID), eq(AuditService.SESSIONS_REVOKED), any(), any(), any());
        }

        @Test
        @DisplayName("an account that does not exist is a 404, not a silent no-op")
        void unknownUser() {
            when(users.findById(99L)).thenReturn(Optional.empty());

            assertThrows(ApiException.class, () -> service.revokeSessions(ADMIN_ID, 99L, null));
            verify(refreshTokens, never()).revokeAllByUserId(any());
        }
    }

    @Nested
    @DisplayName("Creating accounts")
    class Creation {

        private AdminDto.CreateUserRequest req(String username, String email, String role) {
            return new AdminDto.CreateUserRequest(username, email, "password123", null, role);
        }

        @Test
        @DisplayName("creates the account, hashes the password, and seeds its unified score row")
        void createsAccount() {
            when(users.existsByEmail(any())).thenReturn(false);
            when(users.existsByUsername(any())).thenReturn(false);

            var row = service.createUser(ADMIN_ID, req("newbie", "New@Example.com", "USER"), null);

            assertEquals("newbie", row.username());
            // Stored lower-cased, so the same address cannot be registered twice in two cases.
            assertEquals("new@example.com", row.email());
            assertEquals("USER", row.role());
            verify(passwordEncoder).encode("password123");
            verify(unifiedScores).save(any());
            verify(audit).record(eq(ADMIN_ID), eq(AuditService.USER_CREATED), eq("USER"),
                contains(":USER"), any());
        }

        @Test
        @DisplayName("an absent role means USER rather than a guess")
        void defaultsToUser() {
            when(users.existsByEmail(any())).thenReturn(false);
            when(users.existsByUsername(any())).thenReturn(false);

            assertEquals("USER",
                service.createUser(ADMIN_ID, req("newbie", "n@example.com", null), null).role());
        }

        @Test
        @DisplayName("cannot be used to mint a super admin")
        void cannotCreateSuperAdmin() {
            ApiException e = assertThrows(ApiException.class, () ->
                service.createUser(ADMIN_ID, req("boss", "boss@example.com", "SUPER_ADMIN"), null));

            assertTrue(e.getMessage().contains("USER or ADMIN"));
            verify(users, never()).save(any());
        }

        @Test
        @DisplayName("a duplicate email is refused before anything is written")
        void rejectsDuplicateEmail() {
            when(users.existsByEmail("taken@example.com")).thenReturn(true);

            assertThrows(ApiException.class, () ->
                service.createUser(ADMIN_ID, req("someone", "taken@example.com", "USER"), null));
            verify(users, never()).save(any());
        }

        @Test
        @DisplayName("a duplicate username is refused before anything is written")
        void rejectsDuplicateUsername() {
            when(users.existsByEmail(any())).thenReturn(false);
            when(users.existsByUsername("taken")).thenReturn(true);

            assertThrows(ApiException.class, () ->
                service.createUser(ADMIN_ID, req("taken", "free@example.com", "USER"), null));
            verify(users, never()).save(any());
        }
    }
}
