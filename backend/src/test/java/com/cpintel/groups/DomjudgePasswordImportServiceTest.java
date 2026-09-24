package com.cpintel.groups;

import com.cpintel.entity.ContestGroup;
import com.cpintel.entity.GroupMember;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.integration.domjudge.DomjudgeAccountService;
import com.cpintel.integration.domjudge.DomjudgeCredentialStore;
import com.cpintel.integration.domjudge.DomjudgeDto;
import com.cpintel.repository.jpa.ContestGroupRepository;
import com.cpintel.repository.jpa.GroupMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Bulk DOMjudge password changes: which member a row reaches, and what is left alone.
 */
class DomjudgePasswordImportServiceTest {

    private static final Long GROUP_ID = 3L;

    private GroupMemberRepository members;
    private DomjudgeCredentialStore credentials;
    private DomjudgeAccountService accounts;
    private DomjudgePasswordImportService service;

    @BeforeEach
    void setUp() {
        ContestGroupRepository groups = mock(ContestGroupRepository.class);
        members = mock(GroupMemberRepository.class);
        credentials = mock(DomjudgeCredentialStore.class);
        accounts = mock(DomjudgeAccountService.class);

        ContestGroup group = new ContestGroup();
        group.setGroupId(GROUP_ID);
        group.setIsActive(true);
        when(groups.findById(GROUP_ID)).thenReturn(Optional.of(group));

        // asha (11) has login team01 attached; ben (12) has nothing attached.
        when(members.findByGroup(GROUP_ID)).thenReturn(List.of(member(11L, "asha"),
            member(12L, "team02")));
        when(credentials.find(11L)).thenReturn(new DomjudgeCredentialStore.Stored(
            "team01", "old", null, "7", "Team 01", null, null, Instant.EPOCH));

        when(accounts.verify(anyString(), anyString()))
            .thenReturn(new DomjudgeAccountService.Verified(null, null, "7", "Team 01"));
        when(accounts.changePassword(anyLong(), anyString())).thenReturn(status("Team 01"));
        when(accounts.provision(any())).thenReturn(status("Team 02"));

        service = new DomjudgePasswordImportService(groups, members, credentials, accounts);
    }

    private GroupMember member(Long id, String username) {
        return GroupMember.builder()
            .user(User.builder().userId(id).username(username).build()).build();
    }

    private DomjudgeDto.AccountStatus status(String team) {
        return new DomjudgeDto.AccountStatus(true, "x", null, "7", team, null, null, false,
            null, null);
    }

    @Test
    @DisplayName("matches by the attached login, even when the CPIntel username differs")
    void matchesAttachedLogin() {
        var result = service.update(GROUP_ID, "djUsername,djPassword\nteam01,new\n", false);

        assertEquals(DomjudgePasswordImportService.RowStatus.CHANGED,
            result.rows().get(0).status());
        assertEquals("asha", result.rows().get(0).username());
        verify(accounts).changePassword(11L, "new");
    }

    @Test
    @DisplayName("the preview verifies and changes nothing")
    void previewWritesNothing() {
        var result = service.update(GROUP_ID, "djUsername,djPassword\nteam01,new\n", true);

        assertEquals(DomjudgePasswordImportService.RowStatus.WILL_CHANGE,
            result.rows().get(0).status());
        verify(accounts).verify("team01", "new");
        verify(accounts, never()).changePassword(anyLong(), anyString());
        verify(accounts, never()).provision(any());
    }

    @Test
    @DisplayName("a member with nothing attached gets the login of their username attached")
    void attachesExpiredByUsername() {
        var result = service.update(GROUP_ID, "djUsername,djPassword\nteam02,pw2\n", false);

        assertEquals(DomjudgePasswordImportService.RowStatus.ATTACHED,
            result.rows().get(0).status());
        verify(accounts).provision(argThat(r -> r.userId() == 12L
            && "team02".equals(r.username()) && "pw2".equals(r.password())));
    }

    @Test
    @DisplayName("a login nobody in the group has is reported, not attached to someone")
    void unknownLogin() {
        var result = service.update(GROUP_ID, "djUsername,djPassword\nstranger,pw\n", false);

        assertEquals(1, result.notFound());
        verify(accounts, never()).changePassword(anyLong(), anyString());
        verify(accounts, never()).provision(any());
    }

    @Test
    @DisplayName("a rejected password is reported per row and the rest carry on")
    void rejectionIsPerRow() {
        when(accounts.changePassword(11L, "typo"))
            .thenThrow(ApiException.badRequest("DOMjudge rejected that username and password."));

        var result = service.update(GROUP_ID,
            "djUsername,djPassword\nteam01,typo\nteam02,pw2\n", false);

        assertEquals(1, result.failed());
        assertEquals(1, result.ok());
    }

    @Test
    @DisplayName("the password is never echoed back")
    void passwordNotEchoed() {
        var result = service.update(GROUP_ID, "djUsername,djPassword\nteam01,s3cret\n", false);
        assertFalse(result.toString().contains("s3cret"));
    }
}
