package com.cpintel.classrooms;

import com.cpintel.entity.Classroom;
import com.cpintel.entity.ClassroomMember;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.User;
import com.cpintel.events.EventService;
import com.cpintel.events.EventsDto;
import com.cpintel.events.ExamEventService;
import com.cpintel.events.ExamLeaderboardService;
import com.cpintel.exception.ApiException;
import com.cpintel.labs.LabService;
import com.cpintel.labs.LabsDto;
import com.cpintel.repository.jpa.ClassroomMemberRepository;
import com.cpintel.repository.jpa.ContestGroupRepository;
import com.cpintel.repository.jpa.GroupContestRepository;
import com.cpintel.repository.jpa.GroupStandingRepository;
import com.cpintel.repository.jpa.PlatformAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The class dashboard and one student's: lab figures summed per lab and per student, events
 * read from the stored board, and attendance told apart from merely being on the roster.
 */
class ClassroomDashboardServiceTest {

    private static final Long ADMIN = 1L;
    private static final Long ROOM = 3L;
    private static final Long ASHA = 10L;   // solved both lab problems, sat the exam
    private static final Long BHAVIN = 11L; // solved nothing, on the board but never took part
    private static final Long LAB = 50L;
    private static final Long EXAM = 70L;

    private ClassroomMemberRepository members;
    private ClassroomDashboardService service;

    @BeforeEach
    void setUp() {
        ClassroomService classrooms = mock(ClassroomService.class);
        members = mock(ClassroomMemberRepository.class);
        LabService labs = mock(LabService.class);
        GroupContestRepository eventRepository = mock(GroupContestRepository.class);
        EventService events = mock(EventService.class);
        ExamEventService examLog = mock(ExamEventService.class);
        ExamLeaderboardService boards = mock(ExamLeaderboardService.class);

        service = new ClassroomDashboardService(classrooms, members,
            mock(ContestGroupRepository.class), labs, eventRepository,
            mock(GroupStandingRepository.class), events, examLog, boards,
            mock(PlatformAccountRepository.class));

        when(classrooms.requireManaged(ADMIN, ROOM))
            .thenReturn(Classroom.builder().classroomId(ROOM).name("DSA").build());
        User asha = User.builder().userId(ASHA).username("asha").build();
        User bhavin = User.builder().userId(BHAVIN).username("bhavin").build();
        when(members.findByClassroom(ROOM)).thenReturn(List.of(
            ClassroomMember.builder().user(asha).build(),
            ClassroomMember.builder().user(bhavin).build()));
        when(members.findByClassroomIdAndUserUserId(ROOM, ASHA))
            .thenReturn(Optional.of(ClassroomMember.builder().user(asha).build()));

        // A lab of two problems worth 10 and 20.
        Instant now = Instant.now();
        var p1 = new LabsDto.ProblemView(1L, "CODEFORCES", "1A", "One", "u", null, BigDecimal.TEN);
        var p2 = new LabsDto.ProblemView(2L, "CODEFORCES", "1B", "Two", "u", null, BigDecimal.valueOf(20));
        var summary = new LabsDto.LabSummary(LAB, ROOM, "Lab 1", null, now.minus(Duration.ofDays(7)),
            now.minus(Duration.ofDays(1)), false, List.of(), List.of(), null, List.of(p1, p2),
            BigDecimal.valueOf(30), 2, "CLOSED");
        when(labs.list(ROOM)).thenReturn(List.of(summary));
        when(labs.progress(ROOM, LAB)).thenReturn(new LabsDto.Progress(summary, List.of(
            row(ASHA, "asha", 2, 0, BigDecimal.valueOf(30),
                new LabsDto.Cell(1L, "SOLVED", now), new LabsDto.Cell(2L, "SOLVED", now)),
            row(BHAVIN, "bhavin", 0, 0, BigDecimal.ZERO,
                new LabsDto.Cell(1L, null, null), new LabsDto.Cell(2L, null, null)))));

        // An ended exam whose stored board lists both, though only Asha did anything.
        GroupContest exam = GroupContest.builder().contestId(EXAM).classroomId(ROOM).kind("EXAM")
            .platform("DOMJUDGE").name("Midterm").lifecycle("SCHEDULED")
            .startsAt(now.minus(Duration.ofDays(2))).endsAt(now.minus(Duration.ofDays(2)).plus(Duration.ofHours(2)))
            .build();
        when(eventRepository.findByClassroomIdOrderByStartsAtDesc(ROOM)).thenReturn(List.of(exam));
        when(events.participantIds(EXAM)).thenReturn(new LinkedHashSet<>(List.of(ASHA, BHAVIN)));
        when(examLog.summarise(EXAM)).thenReturn(Map.of(
            ASHA, Map.of("EXAM_ENTERED", new long[]{1, 0}, "FOCUS_LOST", new long[]{3, 12})));
        when(boards.stored(exam)).thenReturn(new EventsDto.LeaderboardStandings(
            List.of("A", "B"), Map.of("A", 40.0, "B", 60.0), true,
            List.of(
                new EventsDto.LeaderboardRow(1, ASHA, "asha", null, 1, 55.0, 600, List.of(
                    cell("A", true, 0, 40), cell("B", false, 2, 15))),
                new EventsDto.LeaderboardRow(2, BHAVIN, "bhavin", null, 0, 0, 0, List.of(
                    cell("A", false, 0, 0), cell("B", false, 0, 0)))),
            0, 0, now));
    }

    private static LabsDto.StudentRow row(Long id, String name, int solved, int late,
                                          BigDecimal marks, LabsDto.Cell... cells) {
        return new LabsDto.StudentRow(id, name, null, null, false, null, false, null, false,
            List.of(cells), solved, late, marks);
    }

    private static EventsDto.LeaderboardCell cell(String label, boolean solved, int wrong, double marks) {
        return new EventsDto.LeaderboardCell(label, solved, wrong, solved ? 60L : null, false,
            false, marks, !solved && marks > 0);
    }

    @Test
    @DisplayName("each lab is summed over its students")
    void labColumn() {
        var lab = service.classroom(ADMIN, ROOM).labs().get(0);

        assertEquals(2, lab.studentCount());
        assertEquals(1.0, lab.averageSolved());
        assertEquals(15.0, lab.averageMarks());
        assertEquals(1, lab.finishedAll());
        assertEquals(1, lab.solvedNone());
    }

    @Test
    @DisplayName("an exam counts only those who took part, and its marks out of the paper's total")
    void eventColumn() {
        var exam = service.classroom(ADMIN, ROOM).events().get(0);

        assertEquals("EXAM", exam.kind());
        assertEquals(2, exam.assigned());
        assertEquals(1, exam.sat(), "a board row with nothing done is the roster, not attendance");
        assertEquals(55.0, exam.averageScore());
        assertEquals(100.0, exam.maxScore());
        assertEquals(3, exam.focusLosses());
    }

    @Test
    @DisplayName("each student's line adds up their labs and events")
    void roster() {
        var roster = service.classroom(ADMIN, ROOM).roster();
        var asha = roster.stream().filter(r -> r.userId().equals(ASHA)).findFirst().orElseThrow();
        var bhavin = roster.stream().filter(r -> r.userId().equals(BHAVIN)).findFirst().orElseThrow();

        assertEquals(2, asha.labSolved());
        assertEquals(0, new BigDecimal("30").compareTo(asha.labMarks()));
        assertEquals(1, asha.eventsSat());
        assertEquals(1, asha.events().get(0).rank());
        assertEquals(0, bhavin.eventsSat());
        assertEquals(1, bhavin.eventsAssigned());
        assertFalse(bhavin.events().get(0).sat());
    }

    @Test
    @DisplayName("one student's view has every lab problem and every exam question, hand marks included")
    void student() {
        var view = service.student(ADMIN, ROOM, ASHA);

        assertEquals("DSA", view.classroomName());
        var lab = view.labs().get(0);
        assertEquals(List.of("SOLVED", "SOLVED"),
            lab.problems().stream().map(ClassroomDashboardDto.LabProblem::status).toList());

        var exam = view.events().get(0);
        assertTrue(exam.sat());
        assertEquals(1, exam.rank());
        assertEquals(2, exam.rankedOf());
        assertEquals(55.0, exam.score());
        assertEquals(3, exam.focusLosses());
        assertEquals(15.0, exam.problems().get(1).marks(), "a marker's part marks show");
        assertEquals(2, exam.problems().get(1).wrongAttempts());
    }

    @Test
    @DisplayName("someone outside the classroom is not found, not shown")
    void outsider() {
        when(members.findByClassroomIdAndUserUserId(ROOM, 99L)).thenReturn(Optional.empty());

        assertThrows(ApiException.class, () -> service.student(ADMIN, ROOM, 99L));
    }
}
