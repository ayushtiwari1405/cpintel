package com.cpintel.events;

import com.cpintel.entity.ExamPasscode;
import com.cpintel.entity.User;
import com.cpintel.files.ContestFilePolicy;
import com.cpintel.repository.jpa.*;
import com.cpintel.repository.mongo.CodeSubmissionRepository;
import com.cpintel.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A code that no longer opens a paper must not leave open what it already opened.
 *
 * <p>Reissuing a lost slip, regenerating a leaked roster and withdrawing every code all say the
 * old code is no longer trusted. Whoever already signed in with it holds a session that outlives
 * the code unless it is ended with it.
 */
class PasscodeSessionTest {

    private static final Long ADMIN = 1L;
    private static final Long EXAM = 42L;
    private static final Long CANDIDATE = 7L;
    private static final Long OTHER = 8L;

    private GroupContestRepository events;
    private ContestAssignmentRepository assignments;
    private ExamPasscodeRepository passcodes;
    private ExamAccessService access;
    private ExamPasswordService passwords;
    private EventService service;

    @BeforeEach
    void setUp() {
        events = mock(GroupContestRepository.class);
        assignments = mock(ContestAssignmentRepository.class);
        passcodes = mock(ExamPasscodeRepository.class);
        access = mock(ExamAccessService.class);
        passwords = mock(ExamPasswordService.class);
        service = new EventService(
            events, mock(ContestGroupRepository.class), assignments,
            mock(ContestProblemRepository.class), mock(GroupMemberRepository.class),
            mock(GroupStandingRepository.class), mock(ExamEventRepository.class), passcodes,
            mock(CodeSubmissionRepository.class), mock(UserRepository.class),
            mock(ExamEventService.class), access, passwords, mock(AuditService.class),
            mock(ContestFilePolicy.class), new ObjectMapper(),
            mock(com.cpintel.classrooms.ClassroomService.class));

        when(passcodes.findAllForContest(EXAM)).thenReturn(List.of(row(CANDIDATE), row(OTHER)));
        when(assignments.participantsByTeam(EXAM)).thenReturn(List.of(CANDIDATE, OTHER));
        when(assignments.participantsNamedDirectly(EXAM)).thenReturn(List.of());
    }

    private static ExamPasscode row(Long userId) {
        return ExamPasscode.builder().user(User.builder().userId(userId).build()).build();
    }

    private static ExamPasswordService.Issued issued(Long userId) {
        return new ExamPasswordService.Issued(userId, "u" + userId, null, "CODE", Instant.now(),
            null, 0);
    }

    @Test
    @DisplayName("reissuing a slip ends the session opened with the old code")
    void reissueEndsSession() {
        when(events.isAssignedTo(EXAM, CANDIDATE)).thenReturn(true);
        when(passwords.reissue(ADMIN, EXAM, CANDIDATE, null)).thenReturn(issued(CANDIDATE));

        service.reissuePasscode(ADMIN, EXAM, CANDIDATE, null);

        verify(access).revoke(EXAM, CANDIDATE);
        verify(access, never()).revoke(EXAM, OTHER);
    }

    @Test
    @DisplayName("regenerating every code ends every session the old codes opened")
    void regenerateEndsSessions() {
        when(passwords.issuePasscodes(eq(ADMIN), eq(EXAM), anySet(), eq(true), any()))
            .thenReturn(List.of(issued(CANDIDATE), issued(OTHER)));

        service.issuePasscodes(ADMIN, EXAM, true, null);

        verify(access).revoke(EXAM, CANDIDATE);
        verify(access).revoke(EXAM, OTHER);
    }

    @Test
    @DisplayName("issuing codes to newcomers leaves everyone already signed in alone")
    void additiveIssueKeepsSessions() {
        when(passwords.issuePasscodes(eq(ADMIN), eq(EXAM), anySet(), eq(false), any()))
            .thenReturn(List.of(issued(CANDIDATE), issued(OTHER)));

        service.issuePasscodes(ADMIN, EXAM, false, null);

        verify(access, never()).revoke(anyLong(), anyLong());
    }

    @Test
    @DisplayName("withdrawing every code ends the sessions they opened")
    void withdrawEndsSessions() {
        service.revokePasscodes(ADMIN, EXAM, null);

        verify(passwords).revokePasscodes(ADMIN, EXAM, null);
        verify(access).revoke(EXAM, CANDIDATE);
        verify(access).revoke(EXAM, OTHER);
        verifyNoMoreInteractions(access);
    }

    @Test
    @DisplayName("only holders of a code are signed out, not every assigned candidate")
    void onlyHolders() {
        when(passcodes.findAllForContest(EXAM)).thenReturn(List.of(row(CANDIDATE)));
        when(passwords.issuePasscodes(eq(ADMIN), eq(EXAM), eq(Set.of(CANDIDATE, OTHER)), eq(true),
            any())).thenReturn(List.of(issued(CANDIDATE), issued(OTHER)));

        service.issuePasscodes(ADMIN, EXAM, true, null);

        verify(access).revoke(EXAM, CANDIDATE);
        verify(access, never()).revoke(EXAM, OTHER);
    }
}
