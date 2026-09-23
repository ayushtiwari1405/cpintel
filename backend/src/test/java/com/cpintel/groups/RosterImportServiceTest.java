package com.cpintel.groups;

import com.cpintel.entity.ContestGroup;
import com.cpintel.entity.GroupMember;
import com.cpintel.security.Roles;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.ContestGroupRepository;
import com.cpintel.repository.jpa.GroupMemberRepository;
import com.cpintel.repository.jpa.UnifiedScoreRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * What a bulk import decides, and what it refuses.
 *
 * <p>The consequential parts are all about restraint: an import that creates accounts is
 * irreversible, so the dry run must write nothing, the permission rule must not be reachable
 * around, and a spreadsheet column must never be able to hand out console access.
 */
class RosterImportServiceTest {

    private static final Long ADMIN_ID = 7L;
    private static final Long GROUP_ID = 3L;

    private ContestGroupRepository groups;
    private GroupMemberRepository members;
    private UserRepository users;
    private UnifiedScoreRepository scores;
    private RosterImportService service;

    @BeforeEach
    void setUp() {
        groups = mock(ContestGroupRepository.class);
        members = mock(GroupMemberRepository.class);
        users = mock(UserRepository.class);
        scores = mock(UnifiedScoreRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);

        ContestGroup group = new ContestGroup();
        group.setGroupId(GROUP_ID);
        group.setIsActive(true);
        when(groups.findById(GROUP_ID)).thenReturn(Optional.of(group));

        when(users.findByEmail(anyString())).thenReturn(Optional.empty());
        when(users.findByUsername(anyString())).thenReturn(Optional.empty());
        when(users.existsByUsername(anyString())).thenReturn(false);
        when(members.existsByGroupGroupIdAndUserUserId(anyLong(), anyLong())).thenReturn(false);
        when(encoder.encode(anyString())).thenReturn("hashed");

        AtomicLong ids = new AtomicLong(100);
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setUserId(ids.incrementAndGet());
            return u;
        });
        when(members.save(any(GroupMember.class))).thenAnswer(inv -> inv.getArgument(0));

        service = new RosterImportService(groups, members, users, scores, encoder,
            mock(AuditService.class));
    }

    private RosterImportService.ImportResult run(String text, boolean dryRun, boolean superAdmin) {
        return service.importRoster(ADMIN_ID, GROUP_ID, text, dryRun, superAdmin, null, null);
    }

    private User existing(Long id, String username, String email) {
        return User.builder().userId(id).username(username).email(email).build();
    }

    @Nested
    @DisplayName("dry run")
    class DryRun {

        @Test
        @DisplayName("writes absolutely nothing")
        void writesNothing() {
            // The whole point of the preview. A mis-read column in a 200-row paste is 200 wrong
            // accounts, and there is no undo for a created account.
            var result = run("email,teamName\nasha@uni.edu,Team 01\n", true, true);

            assertTrue(result.dryRun());
            assertEquals(1, result.toCreate());
            verify(users, never()).save(any());
            verify(members, never()).save(any());
            verify(scores, never()).save(any());
        }

        @Test
        @DisplayName("reports each row's fate separately")
        void classifiesEveryRow() {
            when(users.findByEmail("known@uni.edu"))
                .thenReturn(Optional.of(existing(11L, "known", "known@uni.edu")));
            when(users.findByEmail("member@uni.edu"))
                .thenReturn(Optional.of(existing(12L, "member", "member@uni.edu")));
            when(members.existsByGroupGroupIdAndUserUserId(GROUP_ID, 12L)).thenReturn(true);

            var result = run("""
                email
                known@uni.edu
                member@uni.edu
                brand.new@uni.edu
                not-an-email
                known@uni.edu
                """, true, true);

            assertEquals(1, result.toAdd());
            assertEquals(1, result.alreadyMembers());
            assertEquals(1, result.toCreate());
            assertEquals(1, result.invalid());
            assertEquals(1, result.duplicates());
        }
    }

    @Nested
    @DisplayName("permission")
    class Permission {

        @Test
        @DisplayName("a plain admin may create accounts in bulk")
        void plainAdminMayCreate() {
            // Running a contest means adding the people sitting it, and routing every new
            // participant through a super admin turns a class list into an escalation request.
            var result = run("email\nbrand.new@uni.edu\n", false, false);

            assertNull(result.blockedReason());
            assertEquals(1, result.toCreate());
            verify(users).save(any());
        }

        @Test
        @DisplayName("a bulk-created account is always a USER, whoever ran the import")
        void bulkCreatedAccountsAreNeverPrivileged() {
            // This is the property the permission above rests on. If a row could ever set a
            // role, a bulk import would become a way to manufacture console access and the
            // permission would have to move back to SUPER_ADMIN.
            run("email\nbrand.new@uni.edu\n", false, false);

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(users).save(saved.capture());
            assertEquals(Roles.USER, saved.getValue().getRole());
        }

        @Test
        @DisplayName("a plain admin may still add people who already have accounts")
        void plainAdminMayAddExisting() {
            when(users.findByEmail("known@uni.edu"))
                .thenReturn(Optional.of(existing(11L, "known", "known@uni.edu")));
            when(users.getReferenceById(11L))
                .thenReturn(existing(11L, "known", "known@uni.edu"));

            var result = run("email\nknown@uni.edu\n", false, false);

            assertNull(result.blockedReason());
            verify(members).save(any(GroupMember.class));
            verify(users, never()).save(any());
        }
    }

    @Nested
    @DisplayName("the import-wide team")
    class DefaultTeam {

        private RosterImportService.ImportResult runWithTeam(String text, String team) {
            return service.importRoster(ADMIN_ID, GROUP_ID, text, false, false, team, null);
        }

        @Test
        @DisplayName("fills in rows that name no team, which is the whole point")
        void appliesToRowsWithoutATeam() {
            // A class list with no team column at all, everyone on one team. Typing that into
            // two hundred rows is not a reasonable thing to ask of anybody.
            var result = runWithTeam("email\nbrand.new@uni.edu\n", "Team 01");

            assertEquals("Team 01", result.rows().get(0).teamName());
        }

        @Test
        @DisplayName("a team named on the row wins, so a mixed roster keeps its own")
        void perRowWins() {
            var result = runWithTeam(
                "email,teamName\nbrand.new@uni.edu,Their Own Team\n", "Team 01");

            assertEquals("Their Own Team", result.rows().get(0).teamName(),
                "silently overwriting a team somebody typed would be the surprising behaviour");
        }

        @Test
        @DisplayName("blank is the same as not given")
        void blankIsIgnored() {
            var result = runWithTeam("email\nbrand.new@uni.edu\n", "   ");

            assertNull(result.rows().get(0).teamName());
        }
    }

    @Nested
    @DisplayName("account creation")
    class Creation {

        @Test
        @DisplayName("creates the account, adds the member, and returns the password once")
        void createsAndAdds() {
            var result = run("email,fullName,teamName\nasha@uni.edu,Asha Rao,Team 01\n",
                false, true);

            var row = result.rows().get(0);
            assertEquals(RosterImportService.RowStatus.CREATE_AND_ADD, row.status());
            assertNotNull(row.generatedPassword());
            assertFalse(row.generatedPassword().isBlank());

            verify(users).save(argThat(u ->
                "asha@uni.edu".equals(u.getEmail())
                    && "Asha Rao".equals(u.getFullName())
                    // Stored hashed, never as the password that was handed back.
                    && "hashed".equals(u.getPasswordHash())
                    && !row.generatedPassword().equals(u.getPasswordHash())));
            verify(scores).save(any());
            verify(members).save(argThat(m -> "Team 01".equals(m.getExternalHandle())));
        }

        @Test
        @DisplayName("a spreadsheet column can never hand out console access")
        void alwaysCreatesPlainUsers() {
            // There is deliberately no role column. If one were honoured, anyone who could get
            // a file in front of an importing super admin could ask for ADMIN.
            run("email,role\nasha@uni.edu,SUPER_ADMIN\n", false, true);
            verify(users).save(argThat(u -> "USER".equals(u.getRole())));
        }

        @Test
        @DisplayName("derives a username from the address when the sheet has no username column")
        void derivesUsername() {
            run("email\nasha.rao+cp@uni.edu\n", false, true);
            verify(users).save(argThat(u -> u.getUsername().matches("^[a-zA-Z0-9_]+$")));
        }

        @Test
        @DisplayName("does not collide two people whose addresses derive the same username")
        void deduplicatesDerivedUsernames() {
            var result = run("email\nasha@uni.edu\nasha@other.edu\n", false, true);

            String first = result.rows().get(0).username();
            String second = result.rows().get(1).username();
            assertNotEquals(first, second,
                "two different people must not be handed the same username");
        }

        @Test
        @DisplayName("passwords differ between accounts")
        void passwordsAreNotShared() {
            var result = run("email\na@uni.edu\nb@uni.edu\nc@uni.edu\n", false, true);
            long distinct = result.rows().stream()
                .map(RosterImportService.RowOutcome::generatedPassword)
                .distinct().count();
            assertEquals(3, distinct);
        }

        @Test
        @DisplayName("a row with only a username that matches nobody cannot become an account")
        void usernameOnlyRowNeedsAnEmail() {
            var result = run("username\nghost_user\n", true, true);
            assertEquals(RosterImportService.RowStatus.INVALID, result.rows().get(0).status());
            assertTrue(result.rows().get(0).message().toLowerCase().contains("email"));
        }
    }

    @Nested
    @DisplayName("existing members")
    class ExistingMembers {

        @Test
        @DisplayName("re-importing refreshes the judge handle rather than failing")
        void refreshesHandleForExistingMember() {
            // Re-importing a corrected sheet is the normal way a wrong DOMjudge team name gets
            // fixed, and a conflict error would make that the one thing the feature cannot do.
            User user = existing(12L, "member", "member@uni.edu");
            when(users.findByEmail("member@uni.edu")).thenReturn(Optional.of(user));
            when(members.existsByGroupGroupIdAndUserUserId(GROUP_ID, 12L)).thenReturn(true);

            GroupMember membership = GroupMember.builder()
                .user(user).externalHandle("Team OLD").build();
            when(members.findByGroupGroupIdAndUserUserId(GROUP_ID, 12L))
                .thenReturn(Optional.of(membership));

            run("email,teamName\nmember@uni.edu,Team NEW\n", false, true);

            assertEquals("Team NEW", membership.getExternalHandle());
            verify(members).save(membership);
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("an unknown group is refused before anything is parsed")
        void unknownGroup() {
            when(groups.findById(GROUP_ID)).thenReturn(Optional.empty());
            assertThrows(ApiException.class, () -> run("email\na@uni.edu\n", true, true));
        }

        @Test
        @DisplayName("a paste with no rows is refused with a readable message")
        void emptyPaste() {
            ApiException e = assertThrows(ApiException.class, () -> run("email\n", true, true));
            assertTrue(e.getMessage().toLowerCase().contains("no rows"), e.getMessage());
        }

        @Test
        @DisplayName("an unusable header is refused, naming what it found")
        void badHeader() {
            assertThrows(ApiException.class,
                () -> run("fullName,teamName\nAsha Rao,Team 01\n", true, true));
        }
    }
}
