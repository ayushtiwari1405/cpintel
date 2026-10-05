package com.cpintel.evaluation;

import com.cpintel.entity.ContestProblem;
import com.cpintel.entity.ExamMark;
import com.cpintel.entity.ExamTaAssignment;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.User;
import com.cpintel.entity.mongo.CodeSubmission;
import com.cpintel.events.EventService;
import com.cpintel.events.ExamLeaderboardService;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.ClassroomRepository;
import com.cpintel.repository.jpa.ContestProblemRepository;
import com.cpintel.repository.jpa.ExamMarkRepository;
import com.cpintel.repository.jpa.ExamTaAssignmentRepository;
import com.cpintel.repository.jpa.GroupContestRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Who may mark what, which submission they read, and when marks may change.
 */
class EvaluationServiceTest {

    private static final long EXAM = 1L;
    private static final long CLASSROOM = 5L;
    private static final long TA = 9L;
    private static final Instant START = Instant.now().minusSeconds(4 * 3600);

    private final EventService events = mock(EventService.class);
    private final ExamLeaderboardService boards = mock(ExamLeaderboardService.class);
    private final GroupContestRepository eventRepository = mock(GroupContestRepository.class);
    private final ContestProblemRepository problems = mock(ContestProblemRepository.class);
    private final ExamTaAssignmentRepository assignments = mock(ExamTaAssignmentRepository.class);
    private final ExamMarkRepository marks = mock(ExamMarkRepository.class);
    private final ClassroomRepository classrooms = mock(ClassroomRepository.class);
    private final UserRepository users = mock(UserRepository.class);

    private final FakeLocks locks = new FakeLocks();
    private EvaluationService service;

    /** The lock store in memory. */
    static class FakeLocks extends EvaluationLocks {
        final java.util.Map<Long, Instant> frozen = new java.util.HashMap<>();
        final java.util.Set<String> open = new java.util.HashSet<>();
        final List<String> changes = new ArrayList<>();

        FakeLocks() { super(null); }

        @Override public java.util.Map<Long, Instant> freezes(Long e) { return new java.util.HashMap<>(frozen); }
        @Override public void freeze(Long e, Long ta) { frozen.put(ta, Instant.now()); }
        @Override public boolean unfreeze(Long e, Long ta) { return frozen.remove(ta) != null; }
        @Override public java.util.Set<String> reopened(Long e) { return new java.util.HashSet<>(open); }
        @Override public void reopen(Long e, Long u, String l, Long by) { open.add(key(u, l)); }
        @Override public boolean close(Long e, Long u, String l) { return open.remove(key(u, l)); }
        @Override public void recordChange(Long e, Long ta, Long u, String l, String kind, Long by) {
            changes.add(ta + ":" + u + ":" + l + ":" + kind);
        }
        @Override public java.util.Map<Long, java.util.Map<String, Integer>> changeCounts(Long e) {
            return java.util.Map.of();
        }
        @Override public java.util.Map<Long, Integer> totalChanges(Long e) { return java.util.Map.of(); }
    }
    private GroupContest exam;
    private final List<CodeSubmission> rows = new ArrayList<>();
    private final List<ExamTaAssignment> taRows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new EvaluationService(events, boards, eventRepository, problems, assignments,
            marks, classrooms, mock(ClassroomTaService.class), users, mock(AuditService.class),
            locks);

        exam = GroupContest.builder().contestId(EXAM).classroomId(CLASSROOM).kind("EXAM")
            .platform("DOMJUDGE").externalId("5~paper").name("Paper").lifecycle("SCHEDULED")
            .startsAt(START).endsAt(START.plusSeconds(3 * 3600)).build();
        when(events.require(EXAM)).thenReturn(exam);
        when(events.participantIds(EXAM)).thenReturn(new LinkedHashSet<>(List.of(1L, 2L, 3L, TA)));
        when(users.findAllById(any())).thenReturn(List.of(
            user(3, "cs10"), user(1, "cs1"), user(2, "cs2"), user(TA, "ta1")));
        when(problems.findByContestContestIdOrderByOrderingAscLabelAsc(EXAM)).thenReturn(List.of(
            problem("A", 10), problem("B", 10)));
        when(boards.attemptsOf(exam)).thenReturn(rows);
        when(classrooms.isTa(CLASSROOM, TA)).thenReturn(true);
        when(assignments.findByContestIdAndTaUserId(EXAM, TA)).thenReturn(taRows);
        when(assignments.findByContestIdOrderByAssignmentIdAsc(EXAM)).thenReturn(taRows);
        when(marks.findByContestId(EXAM)).thenReturn(List.of());
        when(marks.findByContestIdAndUserIdAndProblemLabel(anyLong(), anyLong(), anyString()))
            .thenReturn(Optional.empty());
    }

    private static User user(long id, String username) {
        return User.builder().userId(id).username(username).build();
    }

    private ContestProblem problem(String label, double points) {
        return ContestProblem.builder().label(label).points(BigDecimal.valueOf(points)).build();
    }

    private void sent(String id, long userId, String label, int secondsIn, String verdict) {
        rows.add(CodeSubmission.builder().id(id).userId(userId).problemIndex(label)
            .verdict(verdict).source("code " + id).submittedAt(START.plusSeconds(secondsIn))
            .build());
    }

    private void assign(String label, String from, String to) {
        taRows.add(ExamTaAssignment.builder().contestId(EXAM).taUserId(TA).problemLabel(label)
            .rangeFrom(from).rangeTo(to).build());
    }

    private static EvaluationDto.MarkRequest mark(long userId, String label, Double value) {
        return new EvaluationDto.MarkRequest(userId, label, value, null);
    }

    @Test
    @DisplayName("Question and range together mean that question for that range")
    void intersection() {
        assign("A", "cs1", "cs2");

        EvaluationDto.Sheet sheet = service.taSheet(TA, EXAM);

        assertEquals("OPEN", sheet.state());
        assertEquals(List.of("A"), sheet.problems().stream().map(EvaluationDto.Problem::label).toList());
        assertEquals(List.of("cs1:A", "cs2:A"),
            sheet.cells().stream().map(c -> c.username() + ":" + c.label()).toList());
    }

    @Test
    @DisplayName("Rows add up, and the range follows natural order")
    void union() {
        assign("B", null, null);
        assign(null, "cs2", "cs10");

        EvaluationDto.Sheet sheet = service.taSheet(TA, EXAM);

        assertEquals(List.of("cs1:B", "cs2:A", "cs2:B", "cs10:A", "cs10:B"),
            sheet.cells().stream().map(c -> c.username() + ":" + c.label()).toList());
    }

    @Test
    @DisplayName("Nobody marks their own paper")
    void notOwnPaper() {
        assign(null, null, null);

        EvaluationDto.Sheet sheet = service.taSheet(TA, EXAM);

        assertTrue(sheet.cells().stream().noneMatch(c -> c.userId() == TA));
        assertThrows(ApiException.class, () -> service.taMark(TA, EXAM, mark(TA, "A", 10.0), null));
    }

    @Test
    @DisplayName("The marker reads the latest accepted submission, else the latest")
    void chosenSubmission() {
        assign(null, null, null);
        sent("a1", 1, "A", 100, "OK");
        sent("a2", 1, "A", 200, "OK");
        sent("a3", 1, "A", 300, "WRONG_ANSWER");
        sent("b1", 1, "B", 100, "WRONG_ANSWER");
        sent("b2", 1, "B", 200, "TIME_LIMIT_EXCEEDED");

        EvaluationDto.Sheet sheet = service.taSheet(TA, EXAM);
        EvaluationDto.Cell a = sheet.cells().stream()
            .filter(c -> c.userId() == 1 && c.label().equals("A")).findFirst().orElseThrow();
        EvaluationDto.Cell b = sheet.cells().stream()
            .filter(c -> c.userId() == 1 && c.label().equals("B")).findFirst().orElseThrow();

        assertEquals("a2", a.submission().id());
        assertEquals(10.0, a.autoMarks());
        assertEquals(3, a.attempts());
        assertNull(a.submission().source());
        assertEquals("b2", b.submission().id());
        assertEquals(0.0, b.autoMarks());
        assertEquals("code a2", service.taSubmission(TA, EXAM, 1L, "a").source());
    }

    @Test
    @DisplayName("A mark outside the TA's assignments is refused")
    void outsideScope() {
        assign("A", "cs1", "cs1");

        assertThrows(ApiException.class, () -> service.taMark(TA, EXAM, mark(2, "A", 5.0), null));
        assertThrows(ApiException.class, () -> service.taMark(TA, EXAM, mark(1, "B", 5.0), null));
        assertThrows(ApiException.class,
            () -> service.taSubmission(TA, EXAM, 2L, "A"));
        verify(marks, never()).save(any());
    }

    @Test
    @DisplayName("A mark is saved within the problem's marks, and the board is re-ranked")
    void saves() {
        assign("A", null, null);
        sent("a1", 1, "A", 100, "WRONG_ANSWER");

        assertThrows(ApiException.class, () -> service.taMark(TA, EXAM, mark(1, "A", 10.5), null));

        EvaluationDto.Cell cell = service.taMark(TA, EXAM,
            new EvaluationDto.MarkRequest(1L, "a", 6.255, " nearly "), null);

        ArgumentCaptor<ExamMark> saved = ArgumentCaptor.forClass(ExamMark.class);
        verify(marks).save(saved.capture());
        assertEquals(new BigDecimal("6.26"), saved.getValue().getMarks());
        assertEquals("A", saved.getValue().getProblemLabel());
        assertEquals("nearly", saved.getValue().getRemark());
        assertEquals("a1", saved.getValue().getSubmissionId());
        assertEquals(TA, saved.getValue().getMarkedBy());
        assertEquals(6.26, cell.marks());
        verify(eventRepository).storeLeaderboard(EXAM, null, null);
    }

    @Test
    @DisplayName("Marks open at the end and close once the examination is done")
    void window() {
        assign(null, null, null);

        exam.setEndsAt(Instant.now().plusSeconds(600));
        assertEquals("NOT_ENDED", service.taSheet(TA, EXAM).state());
        assertTrue(service.taSheet(TA, EXAM).cells().isEmpty());
        assertThrows(ApiException.class, () -> service.taMark(TA, EXAM, mark(1, "A", 5.0), null));

        exam.setEndsAt(Instant.now().minusSeconds(60));
        exam.setCompletedAt(Instant.now());
        assertEquals("DONE", service.taSheet(TA, EXAM).state());
        assertThrows(ApiException.class, () -> service.taMark(TA, EXAM, mark(1, "A", 5.0), null));
        verify(marks, never()).save(any());
    }

    @Test
    @DisplayName("Someone who is not a TA here, or has no assignment, sees no such examination")
    void notATa() {
        assertThrows(ApiException.class, () -> service.taSheet(TA, EXAM));   // no assignment
        assign(null, null, null);
        when(classrooms.isTa(CLASSROOM, TA)).thenReturn(false);
        assertThrows(ApiException.class, () -> service.taSheet(TA, EXAM));
    }

    @Test
    @DisplayName("A TA who froze can change only what an admin sent back, until they freeze again")
    void freezeAndReopen() {
        assign("A", null, null);
        sent("a1", 1, "A", 100, "WRONG_ANSWER");

        EvaluationDto.Sheet frozen = service.freeze(TA, EXAM, null);
        assertNotNull(frozen.frozenAt());
        assertTrue(frozen.cells().stream().allMatch(EvaluationDto.Cell::locked));
        assertThrows(ApiException.class, () -> service.taMark(TA, EXAM, mark(1, "A", 5.0), null));

        EvaluationDto.Cell sentBack = service.reopen(99L, EXAM,
            new EvaluationDto.ReopenRequest(1L, "A", true), null);
        assertTrue(sentBack.reopened());
        assertTrue(sentBack.frozen());
        assertEquals(List.of(TA + ":1:A:REOPENED"), locks.changes);

        assertEquals(5.0, service.taMark(TA, EXAM, mark(1, "A", 5.0), null).marks());
        assertThrows(ApiException.class, () -> service.taMark(TA, EXAM, mark(2, "A", 5.0), null));

        service.freeze(TA, EXAM, null);
        assertTrue(locks.open.isEmpty());
        assertThrows(ApiException.class, () -> service.taMark(TA, EXAM, mark(1, "A", 6.0), null));
    }

    @Test
    @DisplayName("An admin can always change a mark; over a frozen TA's it counts as a change")
    void adminChangesFrozen() {
        assign("A", "cs1", "cs1");
        service.freeze(TA, EXAM, null);

        service.adminMark(99L, EXAM, mark(1, "A", 3.0), null);
        service.adminMark(99L, EXAM, mark(2, "A", 3.0), null);   // not the TA's answer

        assertEquals(List.of(TA + ":1:A:ADMIN_CHANGED"), locks.changes);
        assertThrows(ApiException.class, () -> service.reopen(99L, EXAM,
            new EvaluationDto.ReopenRequest(2L, "A", true), null));   // no frozen TA covers it
    }
}
