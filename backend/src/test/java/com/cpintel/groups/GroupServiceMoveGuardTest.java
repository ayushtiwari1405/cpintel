package com.cpintel.groups;

import com.cpintel.entity.ContestGroup;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.GroupMember;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.ContestAssignmentRepository;
import com.cpintel.repository.jpa.ContestGroupRepository;
import com.cpintel.repository.jpa.GroupContestRepository;
import com.cpintel.repository.jpa.GroupMemberRepository;
import com.cpintel.repository.jpa.GroupStandingRepository;
import com.cpintel.repository.jpa.PlatformAccountRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.service.AuditService;
import com.cpintel.classrooms.ClassroomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Taking somebody off a team must not lock them out of an examination that is running for it.
 */
class GroupServiceMoveGuardTest {

    private static final long FROM = 1L, TO = 2L, USER = 7L, EXAM = 50L, CLASSROOM = 3L;

    private final ContestGroupRepository groups = mock(ContestGroupRepository.class);
    private final ContestAssignmentRepository assignments = mock(ContestAssignmentRepository.class);
    private final GroupMemberRepository members = mock(GroupMemberRepository.class);
    private GroupService service;

    @BeforeEach
    void setUp() {
        service = new GroupService(groups, assignments, members, mock(GroupContestRepository.class),
            mock(GroupStandingRepository.class), mock(PlatformAccountRepository.class),
            mock(UserRepository.class), null, null, mock(AuditService.class),
            mock(ClassroomService.class));

        ContestGroup from = ContestGroup.builder().groupId(FROM).name("A").classroomId(CLASSROOM).build();
        ContestGroup to = ContestGroup.builder().groupId(TO).name("B").classroomId(CLASSROOM).build();
        User user = new User();
        user.setUserId(USER);
        user.setUsername("u7");
        GroupMember member = GroupMember.builder().group(from).user(user).build();
        when(groups.findById(TO)).thenReturn(Optional.of(to));
        when(groups.findById(FROM)).thenReturn(Optional.of(from));
        when(members.findByGroupGroupIdAndUserUserId(FROM, USER)).thenReturn(Optional.of(member));
        when(members.existsByGroupGroupIdAndUserUserId(FROM, USER)).thenReturn(true);
        when(members.save(any())).thenAnswer(i -> i.getArgument(0));
        when(assignments.participantsNamedDirectly(anyLong())).thenReturn(List.of());
    }

    private static GroupContest exam(String kind, String lifecycle, long startOffset, long endOffset) {
        GroupContest e = new GroupContest();
        e.setContestId(EXAM);
        e.setName("Paper");
        e.setKind(kind);
        e.setLifecycle(lifecycle);
        e.setStartsAt(Instant.now().plusSeconds(startOffset));
        e.setEndsAt(Instant.now().plusSeconds(endOffset));
        return e;
    }

    private void teamHas(long group, GroupContest... events) {
        when(assignments.eventsForTeam(group)).thenReturn(List.of(events));
    }

    @Test
    @DisplayName("moving somebody off a team whose examination is running is refused")
    void refusesAMoveDuringARunningExam() {
        teamHas(FROM, exam("EXAM", "SCHEDULED", -600, 3600));
        teamHas(TO);
        assertThrows(ApiException.class, () -> service.moveMember(9L, FROM, USER, TO, null));
        verify(members, never()).delete(any());
    }

    @Test
    @DisplayName("removing somebody from a team whose examination is running is refused")
    void refusesARemovalDuringARunningExam() {
        teamHas(FROM, exam("EXAM", "SCHEDULED", -600, 3600));
        assertThrows(ApiException.class, () -> service.removeMember(9L, FROM, USER, null));
        verify(members, never()).deleteByGroupGroupIdAndUserUserId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("a move is fine when the new team is assigned to the same running paper")
    void allowsAMoveToATeamOnTheSamePaper() {
        GroupContest running = exam("EXAM", "SCHEDULED", -600, 3600);
        teamHas(FROM, running);
        teamHas(TO, running);
        service.moveMember(9L, FROM, USER, TO, null);
        verify(members).delete(any());
    }

    @Test
    @DisplayName("a move is fine when the person is also named on the running paper")
    void allowsAMoveWhenNamedDirectly() {
        teamHas(FROM, exam("EXAM", "SCHEDULED", -600, 3600));
        teamHas(TO);
        when(assignments.participantsNamedDirectly(EXAM)).thenReturn(List.of(USER));
        service.moveMember(9L, FROM, USER, TO, null);
        verify(members).delete(any());
    }

    @Test
    @DisplayName("retiring a team whose examination is running is refused")
    void refusesRetiringDuringARunningExam() {
        teamHas(FROM, exam("EXAM", "SCHEDULED", -600, 3600));
        List<GroupMember> roster = List.of(members.findByGroupGroupIdAndUserUserId(FROM, USER).orElseThrow());
        when(members.findByGroup(FROM)).thenReturn(roster);
        assertThrows(ApiException.class, () -> service.deactivate(9L, FROM, null));
        verify(groups, never()).save(any());
    }

    @Test
    @DisplayName("retiring it is fine when every member is also named on the paper")
    void allowsRetiringWhenAllAreNamed() {
        teamHas(FROM, exam("EXAM", "SCHEDULED", -600, 3600));
        List<GroupMember> roster = List.of(members.findByGroupGroupIdAndUserUserId(FROM, USER).orElseThrow());
        when(members.findByGroup(FROM)).thenReturn(roster);
        when(assignments.participantsNamedDirectly(EXAM)).thenReturn(List.of(USER));
        service.deactivate(9L, FROM, null);
        verify(groups).save(any());
    }

    @Test
    @DisplayName("papers that are not running, and ordinary contests, do not block a move")
    void ignoresPapersThatAreNotRunning() {
        teamHas(FROM,
            exam("EXAM", "SCHEDULED", 3600, 7200),     // not started
            exam("EXAM", "SCHEDULED", -7200, -3600),   // over
            exam("EXAM", "DRAFT", -600, 3600),         // a draft is not live
            exam("CONTEST", "SCHEDULED", -600, 3600)); // practice, not monitored
        teamHas(TO);
        service.moveMember(9L, FROM, USER, TO, null);
        verify(members).delete(any());
    }
}
