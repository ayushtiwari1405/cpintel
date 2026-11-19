package com.cpintel.classrooms;

import com.cpintel.classrooms.ClassroomDashboardDto.ClassDashboard;
import com.cpintel.classrooms.ClassroomDashboardDto.EventCell;
import com.cpintel.classrooms.ClassroomDashboardDto.EventColumn;
import com.cpintel.classrooms.ClassroomDashboardDto.EventProblem;
import com.cpintel.classrooms.ClassroomDashboardDto.Handles;
import com.cpintel.classrooms.ClassroomDashboardDto.LabCell;
import com.cpintel.classrooms.ClassroomDashboardDto.LabColumn;
import com.cpintel.classrooms.ClassroomDashboardDto.RosterRow;
import com.cpintel.classrooms.ClassroomDashboardDto.StudentDashboard;
import com.cpintel.classrooms.ClassroomDashboardDto.StudentEvent;
import com.cpintel.classrooms.ClassroomDashboardDto.StudentLab;
import com.cpintel.entity.Classroom;
import com.cpintel.entity.ClassroomMember;
import com.cpintel.entity.ExamEvent;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.GroupStanding;
import com.cpintel.entity.PlatformAccount;
import com.cpintel.entity.User;
import com.cpintel.events.EventService;
import com.cpintel.events.EventsDto;
import com.cpintel.events.ExamEventService;
import com.cpintel.events.ExamLeaderboardService;
import com.cpintel.exception.ApiException;
import com.cpintel.labs.LabService;
import com.cpintel.labs.LabsDto;
import com.cpintel.labs.ProblemRef;
import com.cpintel.repository.jpa.ClassroomMemberRepository;
import com.cpintel.repository.jpa.ContestGroupRepository;
import com.cpintel.repository.jpa.GroupContestRepository;
import com.cpintel.repository.jpa.GroupStandingRepository;
import com.cpintel.repository.jpa.PlatformAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The classroom dashboards: how the class is doing, and how one student in it is doing.
 *
 * <p>Read-only and built from what is already stored — lab solves, the event's stored
 * leaderboard (TA hand marks included) or, for an event without one, its cached standings,
 * and the examination log for attendance and focus losses. Nothing here recomputes a board or
 * calls a judge, so opening a dashboard costs a handful of queries per lab and event.
 *
 * <p>Only the classroom's own work: a student's private practice is not shown here.
 */
@Service
@RequiredArgsConstructor
public class ClassroomDashboardService {

    private final ClassroomService classrooms;
    private final ClassroomMemberRepository members;
    private final ContestGroupRepository teams;
    private final LabService labs;
    private final GroupContestRepository eventRepository;
    private final GroupStandingRepository standings;
    private final EventService events;
    private final ExamEventService examLog;
    private final ExamLeaderboardService boards;
    private final PlatformAccountRepository accounts;

    // ------------------------------------------------------------------ the class

    @Transactional(readOnly = true)
    public ClassDashboard classroom(Long adminId, Long classroomId) {
        Classroom room = classrooms.requireManaged(adminId, classroomId);
        List<User> students = members.findByClassroom(classroomId).stream()
            .map(ClassroomMember::getUser).toList();

        List<LabsDto.Progress> progress = labs.list(classroomId).stream()
            .map(l -> labs.progress(classroomId, l.labId())).toList();
        List<EventResult> results = eventsOf(classroomId).stream().map(this::result).toList();

        List<LabColumn> labColumns = progress.stream().map(ClassroomDashboardService::labColumn).toList();
        List<EventColumn> eventColumns = results.stream().map(EventResult::column).toList();

        List<RosterRow> roster = new ArrayList<>();
        for (User u : students) roster.add(rosterRow(u, progress, results));

        return new ClassDashboard(classroomId, room.getName(), students.size(), labColumns,
            eventColumns, roster, Instant.now());
    }

    private static LabColumn labColumn(LabsDto.Progress p) {
        LabsDto.LabSummary lab = p.lab();
        int n = p.students().size();
        int problems = lab.problems().size();
        int solvedSum = 0, finished = 0, none = 0;
        BigDecimal marksSum = BigDecimal.ZERO;
        for (LabsDto.StudentRow row : p.students()) {
            solvedSum += row.solved();
            marksSum = marksSum.add(row.marks());
            if (problems > 0 && row.solved() == problems) finished++;
            if (row.solved() == 0 && row.late() == 0) none++;
        }
        return new LabColumn(lab.labId(), lab.title(), lab.opensAt(), lab.dueAt(), lab.state(),
            problems, lab.totalMarks(), n,
            n == 0 ? 0 : (double) solvedSum / n,
            n == 0 ? 0 : marksSum.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP).doubleValue(),
            finished, none);
    }

    private static RosterRow rosterRow(User u, List<LabsDto.Progress> progress,
                                       List<EventResult> results) {
        Long id = u.getUserId();
        List<LabCell> labCells = new ArrayList<>();
        int labSolved = 0, labProblems = 0;
        BigDecimal labMarks = BigDecimal.ZERO, labMax = BigDecimal.ZERO;
        for (LabsDto.Progress p : progress) {
            LabsDto.StudentRow row = p.students().stream()
                .filter(r -> r.userId().equals(id)).findFirst().orElse(null);
            int problems = p.lab().problems().size();
            if (row == null) {
                labCells.add(new LabCell(p.lab().labId(), false, 0, 0, problems, BigDecimal.ZERO));
                continue;
            }
            labCells.add(new LabCell(p.lab().labId(), true, row.solved(), row.late(), problems,
                row.marks()));
            labSolved += row.solved();
            labProblems += problems;
            labMarks = labMarks.add(row.marks());
            labMax = labMax.add(p.lab().totalMarks());
        }

        // Attendance is over the events that have started: one still to come is not missed.
        Instant now = Instant.now();
        List<EventCell> eventCells = new ArrayList<>();
        int assigned = 0, sat = 0, focus = 0;
        for (EventResult r : results) {
            boolean isAssigned = r.assigned().contains(id);
            Standing s = r.board().get(id);
            boolean took = r.sat(id);
            int lost = r.count(id, ExamEvent.Type.FOCUS_LOST);
            if (isAssigned && r.event().hasStarted(now)) assigned++;
            if (isAssigned && took) sat++;
            focus += lost;
            eventCells.add(new EventCell(r.event().getContestId(), isAssigned, took,
                s == null ? null : s.rank(), s == null ? null : s.solved(),
                s == null ? null : s.score(), lost));
        }
        return new RosterRow(id, u.getUsername(), u.getFullName(), labCells, eventCells,
            labSolved, labProblems, labMarks, labMax, assigned, sat, focus);
    }

    // --------------------------------------------------------------- one student

    @Transactional(readOnly = true)
    public StudentDashboard student(Long adminId, Long classroomId, Long userId) {
        Classroom room = classrooms.requireManaged(adminId, classroomId);
        ClassroomMember member = members.findByClassroomIdAndUserUserId(classroomId, userId)
            .orElseThrow(() -> ApiException.notFound("That student is not in this classroom"));
        User u = member.getUser();

        List<String> teamNames = teams.findForMember(userId).stream()
            .filter(t -> classroomId.equals(t.getClassroomId()))
            .map(t -> t.getName()).toList();

        Map<String, PlatformAccount> handles = new HashMap<>();
        for (PlatformAccount a : accounts.findByUserUserId(userId)) handles.put(a.getPlatform(), a);

        List<StudentLab> studentLabs = new ArrayList<>();
        for (LabsDto.LabSummary lab : labs.list(classroomId)) {
            LabsDto.Progress p = labs.progress(classroomId, lab.labId());
            p.students().stream().filter(r -> r.userId().equals(userId)).findFirst()
                .ifPresent(row -> studentLabs.add(studentLab(p.lab(), row)));
        }

        List<StudentEvent> studentEvents = new ArrayList<>();
        for (GroupContest event : eventsOf(classroomId)) {
            EventResult r = result(event);
            if (!r.assigned().contains(userId) && !r.board().containsKey(userId)) continue;
            studentEvents.add(r.forStudent(userId));
        }

        return new StudentDashboard(classroomId, room.getName(), userId, u.getUsername(),
            u.getFullName(), u.getEmail(), member.getJoinedAt(), teamNames,
            handles(handles), studentLabs, studentEvents);
    }

    private static StudentLab studentLab(LabsDto.LabSummary lab, LabsDto.StudentRow row) {
        Map<Long, LabsDto.Cell> cells = new HashMap<>();
        for (LabsDto.Cell c : row.cells()) cells.put(c.problemId(), c);
        List<ClassroomDashboardDto.LabProblem> problems = new ArrayList<>();
        for (LabsDto.ProblemView p : lab.problems()) {
            LabsDto.Cell c = cells.get(p.problemId());
            problems.add(new ClassroomDashboardDto.LabProblem(p.problemId(), p.platform(), p.key(),
                p.title(), p.url(), p.difficulty(), p.marks(),
                c == null ? null : c.status(), c == null ? null : c.solvedAt()));
        }
        return new StudentLab(lab.labId(), lab.title(), lab.opensAt(), lab.dueAt(), lab.state(),
            problems, row.solved(), row.late(), row.marks(), lab.totalMarks());
    }

    private static Handles handles(Map<String, PlatformAccount> h) {
        PlatformAccount cf = h.get(ProblemRef.CODEFORCES);
        PlatformAccount lc = h.get(ProblemRef.LEETCODE);
        PlatformAccount cs = h.get(ProblemRef.CSES);
        return new Handles(
            cf == null ? null : cf.getHandle(), cf != null && cf.getVerifiedAt() != null,
            lc == null ? null : lc.getHandle(), lc != null && lc.getVerifiedAt() != null,
            cs == null ? null : cs.getHandle(),
            cs != null && cs.getVerifiedAt() != null && cs.getAccessToken() != null
                && !"FAILED".equals(cs.getSyncStatus()));
    }

    // ------------------------------------------------------------------ events

    /** The classroom's events that have been set up for real; drafts are left out. */
    private List<GroupContest> eventsOf(Long classroomId) {
        Instant now = Instant.now();
        return eventRepository.findByClassroomIdOrderByStartsAtDesc(classroomId).stream()
            .filter(e -> e.effectiveLifecycle(now) != GroupContest.Lifecycle.DRAFT)
            .toList();
    }

    /** One person's line on an event's board. */
    private record Standing(Integer rank, Integer solved, Double score,
                            List<EventsDto.LeaderboardCell> cells) {}

    private EventResult result(GroupContest event) {
        Long id = event.getContestId();
        Set<Long> assigned = events.participantIds(id);
        Map<Long, Map<String, long[]>> log = examLog.summarise(id);

        Map<Long, Standing> board = new HashMap<>();
        Double maxScore = null;
        int problemCount = 0;
        Instant boardAt = null;
        Map<String, Double> marks = Map.of();
        List<String> labels = List.of();

        EventsDto.LeaderboardStandings stored = boards.stored(event);
        if (stored != null) {
            boardAt = stored.generatedAt();
            labels = stored.problems() == null ? List.of() : stored.problems();
            problemCount = labels.size();
            marks = stored.marks() == null ? Map.of() : stored.marks();
            double max = 0;
            for (String label : labels) max += stored.marked() ? marks.getOrDefault(label, 0.0) : 1;
            maxScore = max;
            if (stored.rows() != null) {
                for (EventsDto.LeaderboardRow row : stored.rows()) {
                    board.put(row.userId(), new Standing(row.rank(), row.solved(), row.score(),
                        row.cells() == null ? List.of() : row.cells()));
                }
            }
        } else {
            // Contests ranked on an outside board (Codeforces groups) keep their standings here.
            for (GroupStanding s : standings.findByContest(id)) {
                if (!Boolean.TRUE.equals(s.getFound()) && (s.getSolved() == null || s.getSolved() == 0)) {
                    continue;
                }
                board.put(s.getUser().getUserId(), new Standing(s.getGroupRank(), s.getSolved(),
                    s.getScore() == null ? null : s.getScore().doubleValue(), List.of()));
                boardAt = boardAt == null || s.getComputedAt().isAfter(boardAt) ? s.getComputedAt() : boardAt;
            }
        }
        return new EventResult(event, assigned, log, board, labels, marks,
            stored != null && stored.marked(), maxScore, problemCount, boardAt);
    }

    /** Everything known about how one event went, ready to be read per student or summed. */
    private record EventResult(GroupContest event, Set<Long> assigned,
                               Map<Long, Map<String, long[]>> log, Map<Long, Standing> board,
                               List<String> labels, Map<String, Double> marks, boolean marked,
                               Double maxScore, int problemCount, Instant boardAt) {

        int count(Long userId, ExamEvent.Type type) {
            long[] v = log.getOrDefault(userId, Map.of()).get(type.name());
            return v == null ? 0 : (int) v[0];
        }

        /**
         * Took part: entered the paper, or did something on the board. A board row with nothing
         * solved and no attempts is just the roster, not attendance.
         */
        boolean sat(Long userId) {
            if (count(userId, ExamEvent.Type.EXAM_ENTERED) > 0) return true;
            Standing s = board.get(userId);
            if (s == null) return false;
            if (s.solved() != null && s.solved() > 0) return true;
            return s.cells().stream().anyMatch(c -> c.solved() || c.wrongAttempts() > 0);
        }

        EventColumn column() {
            int took = 0, focus = 0, scored = 0, ranked = 0, solvedSum = 0;
            double scoreSum = 0;
            for (Long id : assigned) {
                focus += count(id, ExamEvent.Type.FOCUS_LOST);
                if (!sat(id)) continue;
                took++;
                Standing s = board.get(id);
                if (s == null) continue;
                if (s.score() != null) { scoreSum += s.score(); scored++; }
                if (s.solved() != null) { solvedSum += s.solved(); ranked++; }
            }
            Instant now = Instant.now();
            return new EventColumn(event.getContestId(), event.isExam() ? "EXAM" : "CONTEST",
                event.getName(), event.getPlatform(), event.getStartsAt(), event.getEndsAt(),
                event.effectiveLifecycle(now).name(), assigned.size(), took, problemCount,
                scored == 0 ? null : scoreSum / scored, maxScore,
                ranked == 0 ? null : (double) solvedSum / ranked, focus, boardAt);
        }

        StudentEvent forStudent(Long userId) {
            Standing s = board.get(userId);
            List<EventProblem> problems = new ArrayList<>();
            if (s != null && !labels.isEmpty()) {
                Map<String, EventsDto.LeaderboardCell> byLabel = new HashMap<>();
                for (EventsDto.LeaderboardCell c : s.cells()) byLabel.put(c.label(), c);
                for (String label : labels) {
                    EventsDto.LeaderboardCell c = byLabel.get(label);
                    Double max = marked ? marks.getOrDefault(label, 0.0) : 1.0;
                    problems.add(new EventProblem(label, max, c != null && c.solved(),
                        c == null ? 0 : c.wrongAttempts(), c == null ? 0.0 : c.marks()));
                }
            }
            Instant now = Instant.now();
            return new StudentEvent(event.getContestId(), event.isExam() ? "EXAM" : "CONTEST",
                event.getName(), event.getPlatform(), event.getStartsAt(), event.getEndsAt(),
                event.effectiveLifecycle(now).name(), sat(userId),
                s == null ? null : s.rank(), board.isEmpty() ? null : board.size(),
                s == null ? null : s.solved(), s == null ? null : s.score(), maxScore,
                count(userId, ExamEvent.Type.PROBLEM_SUBMITTED),
                count(userId, ExamEvent.Type.FOCUS_LOST), problems, boardAt);
        }
    }
}
