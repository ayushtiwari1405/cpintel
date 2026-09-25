package com.cpintel.events;

import com.cpintel.entity.AuditLog;
import com.cpintel.entity.ExamEvent;
import com.cpintel.entity.User;
import com.cpintel.repository.jpa.AuditLogRepository;
import com.cpintel.repository.jpa.ExamEventRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The suspicious-activity list: what crosses a threshold, and — as much the point — what an
 * ordinary session does that must not.
 */
class ExamFlagServiceTest {

    private static final Long EXAM = 42L;
    private static final Instant T0 = Instant.parse("2026-09-25T09:00:00Z");

    private ExamEventRepository examEvents;
    private AuditLogRepository auditLog;
    private ExamFlagService service;

    private final User candidate = User.builder().userId(7L).username("candidate").build();
    private final List<ExamEvent> log = new ArrayList<>();

    @BeforeEach
    void setUp() {
        examEvents = mock(ExamEventRepository.class);
        auditLog = mock(AuditLogRepository.class);
        service = new ExamFlagService(mock(EventService.class), examEvents, auditLog,
            mock(UserRepository.class));
        when(examEvents.findTypesForContest(eq(EXAM), anyCollection())).thenReturn(log);
        when(auditLog.findByActionAndEntityTypeAndEntityIdOrderByCreatedAtAsc(
            any(), any(), any())).thenReturn(List.of());
    }

    private void at(int seconds, ExamEvent.Type type, String problem, Long durationMs) {
        log.add(ExamEvent.builder().user(candidate).eventId("e" + log.size()).type(type.name())
            .problemLabel(problem).durationMs(durationMs).occurredAt(T0.plusSeconds(seconds))
            .build());
    }

    private List<String> kinds() {
        return service.flags(EXAM).flags().stream().map(EventsDto.Flag::kind).toList();
    }

    @Test
    @DisplayName("An ordinary session produces nothing")
    void ordinarySessionIsClean() {
        at(0, ExamEvent.Type.EXAM_ENTERED, null, null);
        at(5, ExamEvent.Type.PROBLEM_OPENED, "A", null);
        at(20, ExamEvent.Type.FOCUS_LOST, "A", null);
        at(25, ExamEvent.Type.FOCUS_REGAINED, "A", 5_000L);
        at(600, ExamEvent.Type.PROBLEM_SUBMITTED, "A", null);
        // A quick resubmission after a wrong answer is normal.
        at(610, ExamEvent.Type.PROBLEM_SUBMITTED, "A", null);

        assertTrue(kinds().isEmpty());
    }

    @Test
    @DisplayName("A long absence, and many short ones, are flagged separately")
    void absences() {
        at(0, ExamEvent.Type.EXAM_ENTERED, null, null);
        at(100, ExamEvent.Type.FOCUS_REGAINED, null, 90_000L);
        for (int i = 0; i < 5; i++) at(200 + i, ExamEvent.Type.FOCUS_LOST, null, null);

        assertEquals(List.of("FREQUENT_AWAY", "LONG_AWAY"), kinds());
    }

    @Test
    @DisplayName("A first submission seconds after opening the problem is flagged")
    void fastSubmission() {
        at(0, ExamEvent.Type.EXAM_ENTERED, null, null);
        at(300, ExamEvent.Type.PROBLEM_OPENED, "B", null);
        at(312, ExamEvent.Type.PROBLEM_SUBMITTED, "B", null);

        EventsDto.Flag flag = service.flags(EXAM).flags().getFirst();
        assertEquals("FAST_SUBMISSION", flag.kind());
        assertEquals("B", flag.problemLabel());
        assertTrue(flag.detail().contains("12s"));
    }

    @Test
    @DisplayName("Two sign-ins from different addresses are flagged high")
    void multipleSignIns() {
        when(auditLog.findByActionAndEntityTypeAndEntityIdOrderByCreatedAtAsc(
            AuditService.EXAM_SIGN_IN, "EXAM", "42")).thenReturn(List.of(
                AuditLog.builder().userId(7L).ipAddress("10.0.0.1").createdAt(T0).build(),
                AuditLog.builder().userId(7L).ipAddress("10.0.0.2")
                    .createdAt(T0.plusSeconds(60)).build()));
        at(0, ExamEvent.Type.EXAM_ENTERED, null, null);

        EventsDto.Flag flag = service.flags(EXAM).flags().getFirst();
        assertEquals("MULTIPLE_SIGN_INS", flag.kind());
        assertEquals("HIGH", flag.severity());
    }
}
