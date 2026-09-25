package com.cpintel.events;

import com.cpintel.entity.GroupContest;
import com.cpintel.repository.jpa.ContestAssignmentRepository;
import com.cpintel.repository.jpa.GroupContestRepository;
import com.cpintel.repository.jpa.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Who is held out of ordinary mode, and for exactly how long. */
class ExamLockoutServiceTest {

    private GroupContestRepository events;
    private ContestAssignmentRepository assignments;
    private RefreshTokenRepository tokens;
    private ExamLockoutService service;

    @BeforeEach
    void setUp() {
        events = mock(GroupContestRepository.class);
        assignments = mock(ContestAssignmentRepository.class);
        tokens = mock(RefreshTokenRepository.class);
        service = new ExamLockoutService(events, assignments, tokens);
        when(assignments.participantsByTeam(7L)).thenReturn(List.of(1L, 2L));
        when(assignments.participantsNamedDirectly(7L)).thenReturn(List.of(3L));
    }

    private void paper(Instant starts, Instant ends) {
        when(events.findExamsOverlapping(any(), any())).thenReturn(List.of(GroupContest.builder()
            .contestId(7L).name("Midsem").kind("EXAM").lifecycle("SCHEDULED")
            .startsAt(starts).endsAt(ends).build()));
    }

    @Test
    @DisplayName("a running paper holds its candidates, by team and by name, and nobody else")
    void running() {
        paper(Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
        assertTrue(service.lockFor(1L).isPresent());
        assertTrue(service.lockFor(3L).isPresent());
        assertEquals("Midsem", service.lockFor(2L).orElseThrow().name());
        assertTrue(service.lockFor(99L).isEmpty());
    }

    @Test
    @DisplayName("nobody is held before the start or after the end")
    void outsideTheWindow() {
        paper(Instant.now().plusSeconds(120), Instant.now().plusSeconds(3600));
        assertTrue(service.lockFor(1L).isEmpty());

        service = new ExamLockoutService(events, assignments, tokens);
        paper(Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1));
        assertTrue(service.lockFor(1L).isEmpty());
    }

    @Test
    @DisplayName("the database is read once per refresh, not once per request")
    void snapshot() {
        paper(Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
        for (int i = 0; i < 20; i++) service.lockFor(1L);
        verify(events, times(1)).findExamsOverlapping(any(), any());
    }

    @Test
    @DisplayName("the sweep ends ordinary sessions only while a paper runs")
    void sweep() {
        paper(Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
        service.endOrdinarySessions();
        verify(tokens).revokeOrdinarySessions(Set.of(1L, 2L, 3L));

        service = new ExamLockoutService(events, assignments, tokens);
        paper(Instant.now().plusSeconds(120), Instant.now().plusSeconds(3600));
        service.endOrdinarySessions();
        verifyNoMoreInteractions(tokens);
    }
}
