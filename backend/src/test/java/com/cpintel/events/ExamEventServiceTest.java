package com.cpintel.events;

import com.cpintel.entity.ExamEvent;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.ExamEventRepository;
import com.cpintel.repository.jpa.GroupContestRepository;
import com.cpintel.repository.jpa.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * What an examination client is allowed to say about itself.
 *
 * <p>This is the one place in the product where what is stored is evidence about a person, so
 * the rules that keep it worth relying on are the ones worth testing: only the candidate it is
 * about may file it, only for an examination they are actually sitting, a retry cannot inflate
 * the count, a client clock cannot place an event outside the paper it belongs to, and a type
 * nobody recognises is dropped rather than written down.
 */
class ExamEventServiceTest {

    private static final Long USER = 7L;
    private static final Long EXAM = 42L;

    private ExamEventRepository events;
    private GroupContestRepository exams;
    private UserRepository users;
    private ExamEventService service;

    private Instant start;
    private Instant end;

    @BeforeEach
    void setUp() {
        events = mock(ExamEventRepository.class);
        exams = mock(GroupContestRepository.class);
        users = mock(UserRepository.class);
        service = new ExamEventService(events, exams, users);
        ReflectionTestUtils.setField(service, "retentionDays", 365);

        start = Instant.now().minus(Duration.ofMinutes(30));
        end = Instant.now().plus(Duration.ofMinutes(30));

        GroupContest exam = GroupContest.builder()
            .contestId(EXAM)
            .kind(GroupContest.Kind.EXAM.name())
            .lifecycle("SCHEDULED")
            .platform("DOMJUDGE")
            .externalId("midsem")
            .name("Mid-semester practical")
            .startsAt(start)
            .endsAt(end)
            .build();

        when(exams.isAssignedTo(EXAM, USER)).thenReturn(true);
        when(exams.findById(EXAM)).thenReturn(Optional.of(exam));
        when(users.findById(USER)).thenReturn(Optional.of(
            User.builder().userId(USER).username("candidate").build()));
    }

    private EventsDto.ClientEvent event(String id, String type, Instant at) {
        return new EventsDto.ClientEvent(id, type, null, null, null, at);
    }

    @SuppressWarnings("unchecked")
    private List<ExamEvent> saved() {
        ArgumentCaptor<List<ExamEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(events).saveAll(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("Who may file an event")
    class Provenance {

        @Test
        @DisplayName("somebody not sitting the examination is refused")
        void notAssignedIsRefused() {
            when(exams.isAssignedTo(EXAM, 99L)).thenReturn(false);

            assertThrows(ApiException.class, () -> service.report(99L, EXAM,
                new EventsDto.EventReport(List.of(
                    event("a", "FOCUS_LOST", Instant.now())))));

            verify(events, never()).saveAll(any());
        }

        @Test
        @DisplayName("not-yours and no-such-examination are the same answer")
        void doesNotConfirmExistence() {
            // Telling somebody an examination exists and they were left off it is a
            // conversation for their invigilator, not something the API volunteers.
            when(exams.isAssignedTo(EXAM, 99L)).thenReturn(false);

            ApiException thrown = assertThrows(ApiException.class,
                () -> service.requireParticipant(99L, EXAM));
            assertTrue(thrown.getMessage().toLowerCase().contains("no such"));
        }
    }

    @Nested
    @DisplayName("What is written down")
    class Storing {

        @Test
        @DisplayName("a recognised event inside the window is stored")
        void storesOrdinaryEvent() {
            int stored = service.report(USER, EXAM, new EventsDto.EventReport(List.of(
                event("e1", "FOCUS_LOST", Instant.now()))));

            assertEquals(1, stored);
            assertEquals("FOCUS_LOST", saved().get(0).getType());
        }

        @Test
        @DisplayName("a type nobody recognises is dropped rather than stored")
        void dropsUnknownType() {
            // An unreadable row in a log somebody will be judged against is worse than no row.
            int stored = service.report(USER, EXAM, new EventsDto.EventReport(List.of(
                event("e1", "CHEATED", Instant.now()))));

            assertEquals(0, stored);
            verify(events, never()).saveAll(any());
        }

        @Test
        @DisplayName("an event already stored is skipped, so a retry cannot inflate the log")
        void skipsDuplicates() {
            when(events.existsByContestContestIdAndUserUserIdAndEventId(EXAM, USER, "e1"))
                .thenReturn(true);

            int stored = service.report(USER, EXAM, new EventsDto.EventReport(List.of(
                event("e1", "FOCUS_LOST", Instant.now()),
                event("e2", "FOCUS_REGAINED", Instant.now()))));

            assertEquals(1, stored);
            assertEquals("e2", saved().get(0).getEventId());
        }

        @Test
        @DisplayName("an empty report is not an error")
        void emptyReportIsFine() {
            assertEquals(0, service.report(USER, EXAM, new EventsDto.EventReport(List.of())));
        }

        @Test
        @DisplayName("more events than one report may carry is refused outright")
        void refusesOversizedReport() {
            List<EventsDto.ClientEvent> huge = new java.util.ArrayList<>();
            for (int i = 0; i < 201; i++) {
                huge.add(event("e" + i, "FOCUS_LOST", Instant.now()));
            }

            assertThrows(ApiException.class,
                () -> service.report(USER, EXAM, new EventsDto.EventReport(huge)));
        }
    }

    @Nested
    @DisplayName("The client's clock")
    class Clock {

        @Test
        @DisplayName("an event from long before the paper is dropped")
        void dropsWildlyEarly() {
            int stored = service.report(USER, EXAM, new EventsDto.EventReport(List.of(
                event("e1", "FOCUS_LOST", start.minus(Duration.ofHours(4))))));

            assertEquals(0, stored);
        }

        @Test
        @DisplayName("an event from long after it is dropped")
        void dropsWildlyLate() {
            int stored = service.report(USER, EXAM, new EventsDto.EventReport(List.of(
                event("e1", "FOCUS_LOST", end.plus(Duration.ofHours(4))))));

            assertEquals(0, stored);
        }

        @Test
        @DisplayName("ordinary clock skew is pulled to the boundary rather than thrown away")
        void clampsSmallSkew() {
            // A few seconds out is a normal machine, not a lie, and discarding those would
            // lose real events from candidates whose clocks are merely wrong.
            Instant slightlyEarly = start.minus(Duration.ofSeconds(20));

            int stored = service.report(USER, EXAM, new EventsDto.EventReport(List.of(
                event("e1", "EXAM_ENTERED", slightlyEarly))));

            assertEquals(1, stored);
            assertEquals(start, saved().get(0).getOccurredAt());
        }
    }

    @Nested
    @DisplayName("Retention")
    class Retention {

        @Test
        @DisplayName("the sweep deletes by when CPIntel recorded an event, not when a client says")
        void purgesByRecordedAt() {
            when(events.deleteRecordedBefore(any())).thenReturn(12);

            assertEquals(12, service.purgeExpired());
            verify(events).deleteRecordedBefore(any());
        }

        @Test
        @DisplayName("retention switched off keeps everything")
        void zeroMeansKeep() {
            ReflectionTestUtils.setField(service, "retentionDays", 0);

            assertEquals(0, service.purgeExpired());
            verify(events, never()).deleteRecordedBefore(any());
        }
    }
}
