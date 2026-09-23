package com.cpintel.events;

import com.cpintel.entity.ExamEvent;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.User;
import com.cpintel.groups.ContestMonitorRegistry;
import com.cpintel.repository.jpa.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * What an invigilator sees while an examination is running.
 *
 * <p>The interesting part is the status, because it is derived from three sources that can
 * disagree: what the candidate last did, whether anything is reporting from their machine right
 * now, and whether the paper is still open. The cases below are the ones where getting the
 * precedence wrong would mislead somebody supervising a room — a candidate who has finished
 * reading as "away", or one who closed the window reading as "active".
 */
class ExamMonitorServiceTest {

    private static final Long EXAM = 42L;
    private static final Long CANDIDATE = 7L;

    private EventService events;
    private ExamEventService examEvents;
    private ContestMonitorRegistry monitors;
    private UserRepository users;
    private ExamMonitorService service;

    private GroupContest exam;

    @BeforeEach
    void setUp() {
        events = mock(EventService.class);
        examEvents = mock(ExamEventService.class);
        monitors = mock(ContestMonitorRegistry.class);
        users = mock(UserRepository.class);
        service = new ExamMonitorService(events, examEvents, monitors, users);

        exam = GroupContest.builder()
            .contestId(EXAM)
            .kind(GroupContest.Kind.EXAM.name())
            .lifecycle("SCHEDULED")
            .platform("DOMJUDGE")
            .externalId("midsem")
            .name("Paper")
            .startsAt(Instant.now().minus(Duration.ofMinutes(10)))
            .endsAt(Instant.now().plus(Duration.ofHours(2)))
            .build();

        when(events.require(EXAM)).thenReturn(exam);
        when(events.participantIds(EXAM)).thenReturn(Set.of(CANDIDATE));
        when(events.summarise(eq(exam), any())).thenReturn(null);
        when(users.findAllById(any())).thenReturn(List.of(
            User.builder().userId(CANDIDATE).username("candidate").fullName("A Candidate")
                .build()));
        when(examEvents.summarise(EXAM)).thenReturn(Map.of());
        when(examEvents.latestPerUser(EXAM)).thenReturn(List.of());
        when(monitors.isMonitored(CANDIDATE, EXAM)).thenReturn(true);
    }

    private ExamEvent last(ExamEvent.Type type, Instant at) {
        return ExamEvent.builder()
            .contest(exam)
            .user(User.builder().userId(CANDIDATE).username("candidate").build())
            .eventId("x")
            .type(type.name())
            .occurredAt(at)
            .build();
    }

    private EventsDto.MonitorRow row() {
        EventsDto.MonitorSnapshot snapshot = service.snapshot(EXAM);
        assertEquals(1, snapshot.rows().size());
        return snapshot.rows().get(0);
    }

    @Test
    @DisplayName("somebody assigned who has done nothing has not started")
    void neverArrived() {
        // The roster is why they appear at all: a list built from events alone would simply
        // omit the candidate who never turned up, which is the one worth noticing.
        EventsDto.MonitorRow row = row();

        assertEquals("NOT_STARTED", row.status());
        assertNull(row.lastActivityAt());
    }

    @Test
    @DisplayName("away outranks active, and the absence still running is counted")
    void awayNow() {
        Instant since = Instant.now().minus(Duration.ofSeconds(90));
        when(examEvents.latestPerUser(EXAM))
            .thenReturn(List.of(last(ExamEvent.Type.FOCUS_LOST, since)));

        EventsDto.MonitorRow row = row();

        assertEquals("AWAY", row.status());
        assertFalse(row.focused());
        // An absence with no end yet has no duration stored; leaving it out until somebody
        // came back would show a minute and a half away as zero.
        assertTrue(row.awayMs() >= 80_000, "away time should include the absence in progress");
    }

    @Test
    @DisplayName("having finished outranks anything about focus")
    void finished() {
        when(examEvents.latestPerUser(EXAM))
            .thenReturn(List.of(last(ExamEvent.Type.EXAM_COMPLETED, Instant.now())));

        assertEquals("SUBMITTED", row().status());
    }

    @Test
    @DisplayName("a monitor that has stopped reporting reads as having left, while it is live")
    void monitorStopped() {
        // The heartbeat is the only thing that can tell a candidate who is behaving from one
        // who closed the window: both are silent in the log.
        when(monitors.isMonitored(CANDIDATE, EXAM)).thenReturn(false);
        when(examEvents.latestPerUser(EXAM))
            .thenReturn(List.of(last(ExamEvent.Type.PROBLEM_OPENED, Instant.now())));

        EventsDto.MonitorRow row = row();

        assertEquals("LEFT", row.status());
        assertFalse(row.monitorAlive());
    }

    @Test
    @DisplayName("after the paper closes, a stopped monitor is just a finished session")
    void monitorStoppedAfterTheEnd() {
        exam.setEndsAt(Instant.now().minus(Duration.ofMinutes(5)));
        when(monitors.isMonitored(CANDIDATE, EXAM)).thenReturn(false);
        when(examEvents.latestPerUser(EXAM))
            .thenReturn(List.of(last(ExamEvent.Type.PROBLEM_OPENED,
                Instant.now().minus(Duration.ofMinutes(10)))));

        assertEquals("ACTIVE", row().status());
    }

    @Test
    @DisplayName("somebody removed from the roster mid-paper still appears, with their session")
    void keepsSessionsOfPeopleNoLongerAssigned() {
        // Their session happened. Hiding it because a team changed would delete the part of
        // the record that mattered.
        when(events.participantIds(EXAM)).thenReturn(Set.of());
        when(examEvents.latestPerUser(EXAM))
            .thenReturn(List.of(last(ExamEvent.Type.PROBLEM_SUBMITTED, Instant.now())));

        EventsDto.MonitorSnapshot snapshot = service.snapshot(EXAM);

        assertEquals(1, snapshot.rows().size());
        assertEquals(0, snapshot.expected(), "the roster is what was expected, and it is empty");
    }

    @Test
    @DisplayName("counts come from the grouped totals rather than from the log")
    void countsFromTotals() {
        when(examEvents.summarise(EXAM)).thenReturn(Map.of(CANDIDATE, Map.of(
            ExamEvent.Type.FOCUS_LOST.name(), new long[] { 3, 0 },
            ExamEvent.Type.FOCUS_REGAINED.name(), new long[] { 3, 45_000 },
            ExamEvent.Type.PROBLEM_SUBMITTED.name(), new long[] { 2, 0 },
            ExamEvent.Type.PROBLEM_OPENED.name(), new long[] { 4, 0 })));
        when(examEvents.latestPerUser(EXAM))
            .thenReturn(List.of(last(ExamEvent.Type.FOCUS_REGAINED, Instant.now())));

        EventsDto.MonitorRow row = row();

        assertEquals(3, row.focusLosses());
        assertEquals(45_000, row.awayMs());
        assertEquals(2, row.submissions());
        assertEquals(4, row.problemsAttempted());
        assertEquals(12, row.events());
    }
}
