package com.cpintel.admin;

import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.mail.MailService;
import com.cpintel.mapper.UserMapper;
import com.cpintel.repository.jpa.AuditLogRepository;
import com.cpintel.repository.jpa.ExamEventRepository;
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
import java.time.Instant;
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
    private ExamEventRepository examEvents;
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
        examEvents = mock(ExamEventRepository.class);
        service = new AdminUserService(users, auditLog, refreshTokens, files, unifiedScores,
            passwordEncoder, new UserMapper(), audit, jwt, examEvents, mock(MailService.class),
            mock(com.cpintel.repository.mongo.CfSubmissionRepository.class));

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

            var row = service.setActive(ADMIN_ID, OTHER_ID, active(false), true, null);

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

            service.setActive(ADMIN_ID, OTHER_ID, active(true), true, null);

            verify(refreshTokens, never()).revokeAllByUserId(any());
            verify(audit).record(eq(ADMIN_ID), eq(AuditService.USER_ACTIVATED), any(), any(), any());
        }

        @Test
        @DisplayName("an admin cannot deactivate their own account")
        void cannotDeactivateSelf() {
            user(ADMIN_ID, "ADMIN", true);

            var e = assertThrows(ApiException.class,
                () -> service.setActive(ADMIN_ID, ADMIN_ID, active(false), true, null));

            assertTrue(e.getMessage().contains("your own account"));
            verify(users, never()).save(any());
        }

        @Test
        @DisplayName("the last remaining admin cannot be deactivated either")
        void cannotDeactivateLastAdmin() {
            user(OTHER_ID, "ADMIN", true);
            when(users.countOtherActiveAdmins(OTHER_ID)).thenReturn(0L);

            assertThrows(ApiException.class,
                () -> service.setActive(ADMIN_ID, OTHER_ID, active(false), true, null));

            verify(users, never()).save(any());
            verify(refreshTokens, never()).revokeAllByUserId(any());
        }

        @Test
        @DisplayName("deactivating an account that is already off is not a second event")
        void noOpDeactivation() {
            user(OTHER_ID, "USER", false);

            service.setActive(ADMIN_ID, OTHER_ID, active(false), true, null);

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

            service.revokeSessions(ADMIN_ID, OTHER_ID, true, null);

            assertTrue(target.getIsActive());
            verify(refreshTokens).revokeAllByUserId(OTHER_ID);
            verify(jwt).revokeUserTokens(OTHER_ID);
            verify(audit).record(eq(ADMIN_ID), eq(AuditService.SESSIONS_REVOKED), any(), any(), any());
        }

        @Test
        @DisplayName("an account that does not exist is a 404, not a silent no-op")
        void unknownUser() {
            when(users.findById(99L)).thenReturn(Optional.empty());

            assertThrows(ApiException.class, () -> service.revokeSessions(ADMIN_ID, 99L, true, null));
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
        @DisplayName("a plain admin may create an ordinary user")
        void adminMayCreateAUser() {
            when(users.existsByEmail(any())).thenReturn(false);
            when(users.existsByUsername(any())).thenReturn(false);

            // Someone running a contest has to be able to add the people sitting it. Creating
            // a participant hands out no privilege, so there is nothing to reserve.
            var row = service.createUser(ADMIN_ID, req("newbie", "new@example.com", "USER"),
                false, null);

            assertEquals("newbie", row.username());
        }

        @Test
        @DisplayName("a plain admin may NOT create an admin, which would be self-promotion")
        void adminMayNotCreateAnAdmin() {
            // The same escalation the role-assignment rule prevents, just spelled differently:
            // an admin who could mint another admin could mint one for themselves.
            assertThrows(ApiException.class, () -> service.createUser(
                ADMIN_ID, req("newadmin", "newadmin@example.com", "ADMIN"), false, null));

            verify(users, never()).save(any());
        }

        @Test
        @DisplayName("a super admin may still create an admin")
        void superAdminMayCreateAnAdmin() {
            when(users.existsByEmail(any())).thenReturn(false);
            when(users.existsByUsername(any())).thenReturn(false);

            var row = service.createUser(
                ADMIN_ID, req("newadmin", "newadmin@example.com", "ADMIN"), true, null);

            assertNotNull(row);
        }

        @Test
        @DisplayName("creates the account, hashes the password, and seeds its unified score row")
        void createsAccount() {
            when(users.existsByEmail(any())).thenReturn(false);
            when(users.existsByUsername(any())).thenReturn(false);

            var row = service.createUser(ADMIN_ID, req("newbie", "New@Example.com", "USER"), true, null);

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
                service.createUser(ADMIN_ID, req("newbie", "n@example.com", null), true, null).role());
        }

        @Test
        @DisplayName("cannot be used to mint a super admin")
        void cannotCreateSuperAdmin() {
            ApiException e = assertThrows(ApiException.class, () ->
                service.createUser(ADMIN_ID, req("boss", "boss@example.com", "SUPER_ADMIN"), true, null));

            assertTrue(e.getMessage().contains("USER or ADMIN"));
            verify(users, never()).save(any());
        }

        @Test
        @DisplayName("a duplicate email is refused before anything is written")
        void rejectsDuplicateEmail() {
            when(users.existsByEmail("taken@example.com")).thenReturn(true);

            assertThrows(ApiException.class, () ->
                service.createUser(ADMIN_ID, req("someone", "taken@example.com", "USER"), true, null));
            verify(users, never()).save(any());
        }

        @Test
        @DisplayName("a duplicate username is refused before anything is written")
        void rejectsDuplicateUsername() {
            when(users.existsByEmail(any())).thenReturn(false);
            when(users.existsByUsername("taken")).thenReturn(true);

            assertThrows(ApiException.class, () ->
                service.createUser(ADMIN_ID, req("taken", "free@example.com", "USER"), true, null));
            verify(users, never()).save(any());
        }
    }

    /**
     * Where the two console tiers part company.
     *
     * <p>An admin runs the room. What they cannot do is reach another console account — because
     * an admin who can deactivate, rename or re-password a peer can remove the person who would
     * have stopped them, and one who can reach a super admin can remove the tier that limits
     * them at all. That is the escalation the role endpoint is reserved for, arrived at from a
     * different screen, which is why the guard sits on every write rather than on the one that
     * looks dangerous.
     */
    @Nested
    @DisplayName("Admins acting on other admins")
    class TierGuard {

        @Test
        @DisplayName("an admin may not deactivate another admin")
        void cannotDeactivateAPeer() {
            User peer = user(OTHER_ID, "ADMIN", true);

            ApiException e = assertThrows(ApiException.class,
                () -> service.setActive(ADMIN_ID, OTHER_ID, active(false), false, null));

            assertTrue(e.getMessage().toLowerCase().contains("super admin"));
            assertTrue(peer.getIsActive());
            verify(users, never()).save(any());
        }

        @Test
        @DisplayName("an admin may not rewrite another admin's details")
        void cannotEditAPeer() {
            user(OTHER_ID, "ADMIN", true);

            assertThrows(ApiException.class, () -> service.updateUser(ADMIN_ID, OTHER_ID,
                new AdminDto.UpdateUserRequest(null, "taken@example.com", null, null),
                false, null));

            // Rewriting a peer's address would let them start that peer's password reset.
            verify(users, never()).save(any());
        }

        @Test
        @DisplayName("an admin may not set another admin's password")
        void cannotRePasswordAPeer() {
            user(OTHER_ID, "ADMIN", true);

            assertThrows(ApiException.class, () -> service.setPassword(ADMIN_ID, OTHER_ID,
                new AdminDto.SetPasswordRequest(null, null), false, null));

            verify(users, never()).save(any());
        }

        @Test
        @DisplayName("an admin may still do all of it to an ordinary user")
        void ordinaryUsersAreTheirs() {
            user(OTHER_ID, "USER", true);

            assertDoesNotThrow(
                () -> service.setActive(ADMIN_ID, OTHER_ID, active(false), false, null));
            assertDoesNotThrow(() -> service.setPassword(ADMIN_ID, OTHER_ID,
                new AdminDto.SetPasswordRequest(null, null), false, null));
        }

        @Test
        @DisplayName("a super admin has no such limit")
        void superAdminMayReachAdmins() {
            user(OTHER_ID, "ADMIN", true);

            assertDoesNotThrow(
                () -> service.setActive(ADMIN_ID, OTHER_ID, active(false), true, null));
        }

        @Test
        @DisplayName("not even a super admin deactivates a super admin from here")
        void superAdminsAreNotDeactivatable() {
            user(OTHER_ID, "SUPER_ADMIN", true);

            ApiException e = assertThrows(ApiException.class,
                () -> service.setActive(ADMIN_ID, OTHER_ID, active(false), true, null));

            // The tier is the fixed point the rest of the permission system hangs from. Two
            // people who can switch each other off is a race, not a policy.
            assertTrue(e.getMessage().contains("bootstrap-email"));
        }
    }

    @Nested
    @DisplayName("Setting somebody's password for them")
    class SettingPasswords {

        @Test
        @DisplayName("a generated password is returned once and stored only as a hash")
        void generatesAndReturnsOnce() {
            User target = user(OTHER_ID, "USER", true);

            var result = service.setPassword(ADMIN_ID, OTHER_ID,
                new AdminDto.SetPasswordRequest(null, "forgot it"), false, null);

            assertNotNull(result.password());
            assertTrue(result.password().length() >= 8);
            assertEquals("hashed", target.getPasswordHash());
            assertNotEquals(result.password(), target.getPasswordHash());
            verify(audit).record(eq(ADMIN_ID), eq(AuditService.PASSWORD_SET_BY_ADMIN),
                any(), any(), any());
        }

        @Test
        @DisplayName("it signs the owner out of everywhere they were")
        void endsSessions() {
            user(OTHER_ID, "USER", true);

            service.setPassword(ADMIN_ID, OTHER_ID,
                new AdminDto.SetPasswordRequest(null, null), false, null);

            verify(refreshTokens).revokeAllByUserId(OTHER_ID);
            verify(jwt).revokeUserTokens(OTHER_ID);
        }

        /**
         * The account goes back to reading as "still the password somebody was given".
         *
         * Which is exactly what it is: an administrator chose this one, so the owner has not
         * set their own password and the roster should not claim they have.
         */
        @Test
        @DisplayName("it clears the mark that says the owner chose their own password")
        void clearsOwnership() {
            User target = user(OTHER_ID, "USER", true);
            target.setPasswordChangedAt(Instant.now());

            service.setPassword(ADMIN_ID, OTHER_ID,
                new AdminDto.SetPasswordRequest(null, null), false, null);

            assertNull(target.getPasswordChangedAt());
        }

        @Test
        @DisplayName("an admin changes their own password from their profile, not from here")
        void notForYourself() {
            user(ADMIN_ID, "ADMIN", true);

            ApiException e = assertThrows(ApiException.class, () -> service.setPassword(
                ADMIN_ID, ADMIN_ID, new AdminDto.SetPasswordRequest(null, null), true, null));

            assertTrue(e.getMessage().toLowerCase().contains("profile"));
        }
    }

    @Nested
    @DisplayName("Deleting an account")
    class Deleting {

        @Test
        @DisplayName("an account with no examination history is deleted outright")
        void deletesWhenClean() {
            User target = user(OTHER_ID, "USER", true);
            when(examEvents.countByUserUserId(OTHER_ID)).thenReturn(0L);

            service.deleteUser(ADMIN_ID, OTHER_ID, null);

            verify(users).delete(target);
            verify(refreshTokens).revokeAllByUserId(OTHER_ID);
            verify(audit).record(eq(ADMIN_ID), eq(AuditService.USER_DELETED),
                any(), any(), any());
        }

        /**
         * A session log is evidence about a paper, possibly one still being marked.
         *
         * Deleting an account should not also be a decision to destroy that, so the refusal
         * names the alternative rather than merely refusing.
         */
        @Test
        @DisplayName("somebody who has sat an examination is deactivated instead")
        void refusesWhenTheyHaveSatAPaper() {
            user(OTHER_ID, "USER", true);
            when(examEvents.countByUserUserId(OTHER_ID)).thenReturn(120L);

            ApiException e = assertThrows(ApiException.class,
                () -> service.deleteUser(ADMIN_ID, OTHER_ID, null));

            assertTrue(e.getMessage().toLowerCase().contains("deactivate"));
            verify(users, never()).delete(any(User.class));
        }

        @Test
        @DisplayName("a super admin cannot be deleted here either")
        void superAdminsSurvive() {
            user(OTHER_ID, "SUPER_ADMIN", true);

            assertThrows(ApiException.class, () -> service.deleteUser(ADMIN_ID, OTHER_ID, null));
            verify(users, never()).delete(any(User.class));
        }

        @Test
        @DisplayName("nor the last admin, nor yourself")
        void lastAdminAndSelfSurvive() {
            user(OTHER_ID, "ADMIN", true);
            when(users.countOtherActiveAdmins(OTHER_ID)).thenReturn(0L);
            assertThrows(ApiException.class, () -> service.deleteUser(ADMIN_ID, OTHER_ID, null));

            user(ADMIN_ID, "SUPER_ADMIN", true);
            assertThrows(ApiException.class, () -> service.deleteUser(ADMIN_ID, ADMIN_ID, null));

            verify(users, never()).delete(any(User.class));
        }
    }
}
