package com.cpintel.integration.domjudge;

import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Attaching a contestant's DOMjudge account: what is verified, and which team wins.
 *
 * Two teams are in play and they are not interchangeable. The judge's team decides where a
 * submission lands and cannot be overridden from here at all; the admin's decides how CPIntel
 * groups the person. The tests below exist because collapsing those two — in either direction —
 * produces a system that looks right and quietly misfiles somebody's contest.
 */
class DomjudgeAccountServiceTest {

    private static final Long USER = 42L;

    private DomjudgeClient domjudge;
    private DomjudgeCredentialStore credentials;
    private DomjudgeAccountService service;

    @BeforeEach
    void setUp() {
        domjudge = mock(DomjudgeClient.class);
        credentials = mock(DomjudgeCredentialStore.class);
        UserRepository users = mock(UserRepository.class);

        when(domjudge.isConfigured()).thenReturn(true);
        when(credentials.isConfigured()).thenReturn(true);
        when(users.existsById(USER)).thenReturn(true);

        service = new DomjudgeAccountService(domjudge, credentials, users);
    }

    private DjModels.User account(String username, String name, String teamId) {
        DjModels.User u = new DjModels.User();
        u.setUsername(username);
        u.setName(name);
        u.setTeam_id(teamId);
        return u;
    }

    /** What DOMjudge 8.0 answers: the team's name, and no id anywhere. */
    private DjModels.User accountNamedTeamOnly(String username, String name, String teamName) {
        DjModels.User u = new DjModels.User();
        u.setUsername(username);
        u.setName(name);
        u.setTeam(teamName);
        return u;
    }

    private DjModels.Team team(String id, String displayName) {
        DjModels.Team t = new DjModels.Team();
        t.setId(id);
        t.setDisplay_name(displayName);
        return t;
    }

    /** A team as 8.0 lists it: a name, and no display name. */
    private DjModels.Team namedTeam(String id, String name) {
        DjModels.Team t = new DjModels.Team();
        t.setId(id);
        t.setName(name);
        return t;
    }

    /** The credentials actually written to the store. */
    private DomjudgeCredentialStore.Stored captureSaved() {
        ArgumentCaptor<DomjudgeCredentialStore.Stored> captor =
            ArgumentCaptor.forClass(DomjudgeCredentialStore.Stored.class);
        verify(credentials).save(eq(USER), captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("verification, before anything is stored")
    class Verification {

        @Test
        @DisplayName("an account with no team is refused, even when the admin names one")
        void teamlessAccountIsRefused() {
            when(domjudge.whoami(any())).thenReturn(account("jury1", "Jury", null));

            // The admin's choice cannot rescue this. It governs grouping, not attribution —
            // the judge reads the team off the login, so submissions would land nowhere.
            ApiException e = assertThrows(ApiException.class, () -> service.provision(
                new DomjudgeDto.ProvisionRequest(USER, "jury1", "pw", "Jury", "t7")));

            assertTrue(e.getMessage().toLowerCase().contains("team"));
            verify(credentials, never()).save(any(), any());
        }

        @Test
        @DisplayName("nothing is stored when the judge cannot be reached")
        void nothingStoredOnFailure() {
            when(domjudge.whoami(any()))
                .thenThrow(ApiException.badRequest("DOMjudge rejected that username and password."));

            assertThrows(ApiException.class, () -> service.provision(
                new DomjudgeDto.ProvisionRequest(USER, "ada", "wrong", "Ada", null)));

            verify(credentials, never()).save(any(), any());
        }
    }

    @Nested
    @DisplayName("judges that report a team differently")
    class ApiVersions {

        @BeforeEach
        void noExistingCredential() {
            when(credentials.find(USER)).thenReturn(null);
        }

        @Test
        @DisplayName("8.0 names the team without numbering it, and the id is resolved from it")
        void resolvesTeamIdFromName() {
            // DOMjudge 8.0's /api/v4/user has no team_id field at all. Reading only that one
            // made every team account on an 8.0 instance look like an admin account with no
            // team, which is the bug this pins.
            when(domjudge.whoami(any()))
                .thenReturn(accountNamedTeamOnly("ada", "Ada L", "test_user1"));
            when(domjudge.getAllTeams(any())).thenReturn(List.of(
                team("t3", "Someone else"), namedTeam("t7", "test_user1")));

            service.provision(
                new DomjudgeDto.ProvisionRequest(USER, "ada", "pw", "Ada Lovelace", null));

            DomjudgeCredentialStore.Stored saved = captureSaved();
            assertEquals("t7", saved.teamId(), "resolved from the name the judge gave");
            assertEquals("test_user1", saved.teamName());
        }

        @Test
        @DisplayName("a named team no contest lists yet still attaches, on the name alone")
        void attachesWithNameWhenIdIsUnknown() {
            // A team registered for no contest cannot be found in any contest's team list.
            // Refusing would block setting people up before the round is configured, which is
            // exactly when an admin does this work.
            when(domjudge.whoami(any()))
                .thenReturn(accountNamedTeamOnly("ada", "Ada L", "test_user1"));
            when(domjudge.getAllTeams(any())).thenReturn(List.of());

            service.provision(
                new DomjudgeDto.ProvisionRequest(USER, "ada", "pw", "Ada Lovelace", null));

            DomjudgeCredentialStore.Stored saved = captureSaved();
            assertNull(saved.teamId(), "the judge never said which id it was");
            assertEquals("test_user1", saved.teamName(), "and the name is what identifies them");
        }

        @Test
        @DisplayName("an account the judge gives neither id nor name for is still refused")
        void refusesWhenThereIsNoTeamAtAll() {
            when(domjudge.whoami(any())).thenReturn(account("jury1", "Jury", null));

            assertThrows(ApiException.class, () -> service.provision(
                new DomjudgeDto.ProvisionRequest(USER, "jury1", "pw", "Jury", null)));
            verify(credentials, never()).save(any(), any());
        }
    }

    @Nested
    @DisplayName("the two teams")
    class Teams {

        @BeforeEach
        void common() {
            when(domjudge.whoami(any())).thenReturn(account("ada", "Ada L", "t7"));
            when(credentials.find(USER)).thenReturn(null);
        }

        @Test
        @DisplayName("with no team named, the judge's answer is used for both")
        void defaultsToTheJudge() {
            when(domjudge.whoami(any())).thenReturn(account("ada", "Ada L", "t7"));

            service.provision(
                new DomjudgeDto.ProvisionRequest(USER, "ada", "pw", "Ada Lovelace", null));

            DomjudgeCredentialStore.Stored saved = captureSaved();
            assertEquals("t7", saved.teamId());
            assertNull(saved.assignedTeamId());
            assertEquals("t7", saved.effectiveTeamId());
            assertFalse(saved.teamMismatch(), "nothing to disagree with when none was named");
        }

        @Test
        @DisplayName("the admin's team is stored beside the judge's, never over it")
        void assignedTeamIsKeptSeparate() {
            when(domjudge.getAllTeams(any())).thenReturn(List.of(team("t9", "Team Beta")));

            service.provision(
                new DomjudgeDto.ProvisionRequest(USER, "ada", "pw", "Ada Lovelace", "t9"));

            DomjudgeCredentialStore.Stored saved = captureSaved();

            // Overwriting teamId here would send the contestant's own verdicts out of their
            // submissions list, because that filter matches on where the judge files them.
            assertEquals("t7", saved.teamId(), "the judge's team must survive an assignment");
            assertEquals("t9", saved.assignedTeamId());
            assertEquals("Team Beta", saved.assignedTeamName());
            assertEquals("t9", saved.effectiveTeamId(), "grouping follows the admin");
            assertTrue(saved.teamMismatch());
        }

        @Test
        @DisplayName("assigning the team the judge already reports is not a mismatch")
        void agreeingAssignmentIsNotAMismatch() {
            when(domjudge.getAllTeams(any())).thenReturn(List.of(team("t7", "Team Alpha")));

            service.provision(
                new DomjudgeDto.ProvisionRequest(USER, "ada", "pw", "Ada Lovelace", "t7"));

            assertFalse(captureSaved().teamMismatch());
        }

        @Test
        @DisplayName("a team the judge will not name is still assigned, by id")
        void unnameableTeamIsStillAssigned() {
            // A build that refuses the team list to this account. Refusing the assignment
            // would be the less useful answer — the admin may know something it cannot see.
            when(domjudge.getAllTeams(any())).thenReturn(List.of());

            service.provision(
                new DomjudgeDto.ProvisionRequest(USER, "ada", "pw", "Ada Lovelace", "t9"));

            DomjudgeCredentialStore.Stored saved = captureSaved();
            assertEquals("t9", saved.assignedTeamId());
            assertNull(saved.assignedTeamName());
        }
    }

    @Nested
    @DisplayName("the display name")
    class Name {

        @BeforeEach
        void common() {
            when(domjudge.whoami(any())).thenReturn(account("ada", "ada-on-judge", "t7"));
        }

        @Test
        @DisplayName("the admin's name is kept when they gave one")
        void adminNameWins() {
            service.provision(
                new DomjudgeDto.ProvisionRequest(USER, "ada", "pw", "  Ada Lovelace  ", null));

            assertEquals("Ada Lovelace", captureSaved().name(), "and trimmed");
        }

        @Test
        @DisplayName("the judge's name is the fallback, so the field is never empty")
        void fallsBackToTheJudge() {
            service.provision(
                new DomjudgeDto.ProvisionRequest(USER, "ada", "pw", "   ", null));

            assertEquals("ada-on-judge", captureSaved().name());
        }
    }

    @Nested
    @DisplayName("changing the password")
    class PasswordChange {

        private DomjudgeCredentialStore.Stored current() {
            return new DomjudgeCredentialStore.Stored("ada", "old-pw", "Ada L", "t7", "Team 7",
                "t9", "Team 9", java.time.Instant.EPOCH);
        }

        @Test
        @DisplayName("keeps the login, name and assigned team, and replaces only the password")
        void replacesOnlyThePassword() {
            when(credentials.find(USER)).thenReturn(current());
            when(domjudge.whoami(any())).thenReturn(account("ada", "Ada L", "t7"));

            service.changePassword(USER, "new-pw");

            DomjudgeCredentialStore.Stored saved = captureSaved();
            assertEquals("ada", saved.username());
            assertEquals("new-pw", saved.password());
            assertEquals("Ada L", saved.name());
            assertEquals("t9", saved.assignedTeamId(), "the admin's choice survives");
            assertTrue(saved.provisioned().isAfter(java.time.Instant.EPOCH));
        }

        @Test
        @DisplayName("verifies the new password as the attached login before storing it")
        void verifiesAsTheAttachedLogin() {
            when(credentials.find(USER)).thenReturn(current());
            when(domjudge.whoami(argThat(c -> c != null && "ada".equals(c.username())
                && "new-pw".equals(c.password())))).thenReturn(account("ada", "Ada L", "t7"));

            service.changePassword(USER, "new-pw");

            verify(credentials).save(eq(USER), any());
        }

        @Test
        @DisplayName("a rejected password leaves the working one in place")
        void rejectedKeepsOld() {
            when(credentials.find(USER)).thenReturn(current());
            when(domjudge.whoami(any()))
                .thenThrow(ApiException.badRequest("DOMjudge rejected that username and password."));

            assertThrows(ApiException.class, () -> service.changePassword(USER, "typo"));
            verify(credentials, never()).save(any(), any());
        }

        @Test
        @DisplayName("refuses when nothing is attached, rather than inventing a login")
        void nothingAttached() {
            when(credentials.find(USER)).thenReturn(null);

            assertThrows(ApiException.class, () -> service.changePassword(USER, "pw"));
            verify(domjudge, never()).whoami(any());
        }
    }
}
