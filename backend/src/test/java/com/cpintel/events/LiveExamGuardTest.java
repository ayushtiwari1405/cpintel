package com.cpintel.events;

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
 * A paper set on a judge contest that already held a practice round.
 *
 * <p>Both sit under the same contest id, so the contest alone cannot say which work is the
 * paper's. These pin down that the practice round's submissions stay out of the paper.
 */
class LiveExamGuardTest {

    private static final Long CANDIDATE = 24L;

    private final Instant start = Instant.now().minus(Duration.ofMinutes(10));
    private GroupContest exam;
    private LiveExamGuard guard;

    @BeforeEach
    void setUp() {
        exam = GroupContest.builder()
            .contestId(22L)
            .kind(GroupContest.Kind.EXAM.name())
            .lifecycle("SCHEDULED")
            .platform("DOMJUDGE")
            .externalId("4")
            .name("Test2")
            .startsAt(start)
            .endsAt(start.plus(Duration.ofHours(1)))
            .build();
        GroupContestRepository events = mock(GroupContestRepository.class);
        when(events.findAllForParticipant(CANDIDATE)).thenReturn(List.of(exam));
        guard = new LiveExamGuard(events);
    }

    @Test
    @DisplayName("only what was submitted inside the paper's window belongs to it")
    void window() {
        assertFalse(LiveExamGuard.madeDuring(exam, start.minus(Duration.ofDays(2))));
        assertTrue(LiveExamGuard.madeDuring(exam, start));
        assertTrue(LiveExamGuard.madeDuring(exam, start.plus(Duration.ofMinutes(5))));
        assertFalse(LiveExamGuard.madeDuring(exam, exam.getEndsAt().plusSeconds(1)));
        assertFalse(LiveExamGuard.madeDuring(exam, null));
    }

    @Test
    @DisplayName("the practice round's source on the same contest cannot be opened mid-paper")
    void practiceSourceRefused() {
        Instant practice = start.minus(Duration.ofDays(2));

        assertThrows(ApiException.class, () ->
            guard.requireSubmissionAllowed(CANDIDATE, "DOMJUDGE", "4", practice));
        assertDoesNotThrow(() -> guard.requireSubmissionAllowed(
            CANDIDATE, "DOMJUDGE", "4", start.plus(Duration.ofMinutes(1))));
    }
}
