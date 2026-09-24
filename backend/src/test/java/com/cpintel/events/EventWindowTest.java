package com.cpintel.events;

import com.cpintel.compete.CompeteDto;
import com.cpintel.entity.GroupContest;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.GroupContestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * A judge contest left running for a fortnight, with a two-hour round set on it that is over.
 */
class EventWindowTest {

    private static final Long USER = 24L;

    private GroupContestRepository events;
    private EventWindow window;

    private final CompeteDto.ContestInfo judge = new CompeteDto.ContestInfo(
        "4", "StacksandQueues", "DOMJUDGE", "CODING", true, false,
        Instant.now().minus(Duration.ofDays(3)), 20_320 * 60L, -3 * 86_400L, 5 * 86_400L,
        true, null, false, null, List.of(), "http://judge/4");

    @BeforeEach
    void setUp() {
        events = mock(GroupContestRepository.class);
        window = new EventWindow(events);
    }

    private GroupContest round(Instant start, Instant end) {
        return GroupContest.builder().contestId(9L).kind("CONTEST").lifecycle("SCHEDULED")
            .platform("DOMJUDGE").externalId("4").name("Stacks And Queues")
            .startsAt(start).endsAt(end).build();
    }

    private void holds(GroupContest... held) {
        when(events.findForParticipant(USER, "DOMJUDGE", "4")).thenReturn(List.of(held));
    }

    @Test
    @DisplayName("once the round is over, the contest is over for its people, whatever the judge says")
    void endedRoundCloses() {
        Instant start = Instant.now().minus(Duration.ofDays(2));
        holds(round(start, start.plus(Duration.ofDays(1))));

        CompeteDto.ContestInfo seen = window.apply(USER, judge);

        assertFalse(seen.running());
        assertEquals("FINISHED", seen.phase());
        assertFalse(seen.submissionsOpen());
        assertEquals(0, seen.secondsRemaining());
        assertThrows(ApiException.class, () -> window.requireOpen(USER, "DOMJUDGE", "4"));
    }

    @Test
    @DisplayName("a round that is running shows its own clock, not the judge's")
    void runningRoundUsesItsClock() {
        Instant start = Instant.now().minus(Duration.ofMinutes(30));
        holds(round(start, start.plus(Duration.ofHours(2))));

        CompeteDto.ContestInfo seen = window.apply(USER, judge);

        assertTrue(seen.running());
        assertTrue(seen.secondsRemaining() <= 90 * 60 && seen.secondsRemaining() > 80 * 60);
        assertEquals(2 * 3600, seen.durationSeconds());
        assertDoesNotThrow(() -> window.requireOpen(USER, "DOMJUDGE", "4"));
    }

    @Test
    @DisplayName("somebody with no round on the contest keeps the judge's window")
    void noRoundKeepsJudge() {
        holds();

        assertSame(judge, window.apply(USER, judge));
        assertDoesNotThrow(() -> window.requireOpen(USER, "DOMJUDGE", "4"));
    }
}
