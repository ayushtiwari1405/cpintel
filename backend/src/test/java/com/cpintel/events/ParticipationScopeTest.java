package com.cpintel.events;

import com.cpintel.classrooms.ClassroomService;
import com.cpintel.entity.GroupContest;
import com.cpintel.repository.jpa.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * A student shared between classrooms has a history in each. An admin reads only theirs.
 */
class ParticipationScopeTest {

    private static final Long READER = 1L;
    private static final Long STUDENT = 5L;

    private GroupContestRepository events;
    private ClassroomService classrooms;
    private EventAnalyticsService service;

    @BeforeEach
    void setUp() {
        events = mock(GroupContestRepository.class);
        classrooms = mock(ClassroomService.class);
        service = new EventAnalyticsService(events, mock(GroupStandingRepository.class),
            mock(ContestGroupRepository.class), mock(GroupMemberRepository.class),
            mock(ContestAssignmentRepository.class), mock(ExamEventService.class),
            mock(EventService.class), classrooms);

        Instant start = Instant.now().minus(Duration.ofDays(2));
        when(events.findAllForParticipant(STUDENT)).thenReturn(List.of(
            exam(10L, 1L, "A midterm", start), exam(99L, 2L, "B midterm", start)));
    }

    private static GroupContest exam(Long id, Long classroom, String name, Instant start) {
        return GroupContest.builder().contestId(id).classroomId(classroom).kind("EXAM")
            .platform("DOMJUDGE").name(name).lifecycle("SCHEDULED")
            .startsAt(start).endsAt(start.plus(Duration.ofHours(2))).build();
    }

    @Test
    @DisplayName("an admin sees only the events of the classrooms they run")
    void scopedToReader() {
        when(classrooms.managedIds(READER)).thenReturn(List.of(1L));

        var rows = service.participation(READER, STUDENT);

        assertEquals(List.of(10L), rows.stream().map(EventsDto.ParticipationRow::eventId).toList());
    }

    @Test
    @DisplayName("a superadmin sees all of them")
    void superAdminSeesAll() {
        when(classrooms.managedIds(READER)).thenReturn(null);

        assertEquals(2, service.participation(READER, STUDENT).size());
    }
}
