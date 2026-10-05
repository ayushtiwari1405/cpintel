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
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Marking an examination by hand.
 *
 * <p>The judge gives each problem all its marks once accepted and none otherwise. Here a
 * teaching assistant, or an admin, reads what each student sent and sets the mark themselves.
 * A mark set here replaces the judge's on the leaderboard and in the export.
 *
 * <p><b>What a marker reads.</b> For each student and question, one submission: the latest
 * accepted one, or the latest of all when none was accepted.
 *
 * <p><b>Who marks what.</b> An admin of the classroom marks everything. A TA marks what their
 * assignments on the examination cover: each one a question, a range of usernames, or that
 * question for that range, adding up across rows. Nobody marks their own paper.
 *
 * <p><b>When.</b> From the end of the examination until an admin marks it done. Before the end
 * there is nothing final to read; after it is done the leaderboard is fixed and exported, so
 * changing a mark means reopening it first.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvaluationService {

    public static final String NOT_ENDED = "NOT_ENDED";
    public static final String OPEN = "OPEN";
    public static final String DONE = "DONE";

    private final EventService events;
    private final ExamLeaderboardService boards;
    private final GroupContestRepository eventRepository;
    private final ContestProblemRepository problemRepository;
    private final ExamTaAssignmentRepository assignmentRepository;
    private final ExamMarkRepository markRepository;
    private final ClassroomRepository classroomRepository;
    private final ClassroomTaService tas;
    private final UserRepository userRepository;
    private final AuditService auditService;

    // ================================================================= TA side

    /** The examinations this TA has work on, newest first. */
    @Transactional(readOnly = true)
    public List<EvaluationDto.TaExam> myExams(Long taId) {
        Map<Long, List<ExamTaAssignment>> byEvent = new LinkedHashMap<>();
        for (ExamTaAssignment row : assignmentRepository.findByTaUserId(taId)) {
            byEvent.computeIfAbsent(row.getContestId(), k -> new ArrayList<>()).add(row);
        }
        Set<Long> taIn = new HashSet<>(classroomRepository.taClassroomIds(taId));
        Instant now = Instant.now();

        List<EvaluationDto.TaExam> out = new ArrayList<>();
        for (Map.Entry<Long, List<ExamTaAssignment>> entry : byEvent.entrySet()) {
            GroupContest event = eventRepository.findById(entry.getKey()).orElse(null);
            if (event == null || !event.isExam() || !taIn.contains(event.getClassroomId())) {
                continue;
            }
            Scope scope = new Scope(taId, false, entry.getValue());
            Paper paper = load(event, false);
            int cells = 0, marked = 0;
            for (User student : paper.students()) {
                for (EvaluationDto.Problem problem : paper.problems()) {
                    if (!scope.covers(student, problem.label())) continue;
                    cells++;
                    if (paper.mark(student.getUserId(), problem.label()) != null) marked++;
                }
            }
            String classroom = classroomRepository.findById(event.getClassroomId())
                .map(c -> c.getName()).orElse(null);
            out.add(new EvaluationDto.TaExam(event.getContestId(), event.getName(), classroom,
                event.getStartsAt(), event.getEndsAt(), state(event, now), cells, marked));
        }
        out.sort(Comparator.comparing(EvaluationDto.TaExam::endsAt,
            Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    @Transactional(readOnly = true)
    public EvaluationDto.Sheet taSheet(Long taId, Long eventId) {
        GroupContest event = events.require(eventId);
        return sheet(taScope(taId, event), event);
    }

    @Transactional(readOnly = true)
    public EvaluationDto.Submission taSubmission(Long taId, Long eventId, Long userId,
                                                 String label) {
        GroupContest event = events.require(eventId);
        return submission(taScope(taId, event), event, userId, label);
    }

    @Transactional
    public EvaluationDto.Cell taMark(Long taId, Long eventId, EvaluationDto.MarkRequest req,
                                     HttpServletRequest httpReq) {
        GroupContest event = events.require(eventId);
        return mark(taScope(taId, event), event, req, httpReq);
    }

    // ============================================================== admin side

    @Transactional(readOnly = true)
    public EvaluationDto.Sheet adminSheet(Long adminId, Long eventId) {
        GroupContest event = requireExam(eventId);
        return sheet(Scope.everything(adminId), event);
    }

    @Transactional(readOnly = true)
    public EvaluationDto.Submission adminSubmission(Long adminId, Long eventId, Long userId,
                                                    String label) {
        return submission(Scope.everything(adminId), requireExam(eventId), userId, label);
    }

    @Transactional
    public EvaluationDto.Cell adminMark(Long adminId, Long eventId, EvaluationDto.MarkRequest req,
                                       HttpServletRequest httpReq) {
        return mark(Scope.everything(adminId), requireExam(eventId), req, httpReq);
    }

    /** Who marks what on this examination, and what there is to hand out. */
    @Transactional(readOnly = true)
    public EvaluationDto.AssignmentBoard board(Long eventId) {
        GroupContest event = requireExam(eventId);
        Paper paper = load(event, false);
        List<EvaluationDto.Ta> classroomTas = tas.tasOf(event.getClassroomId());
        Map<Long, EvaluationDto.Ta> taById = new HashMap<>();
        for (EvaluationDto.Ta ta : classroomTas) taById.put(ta.userId(), ta);

        List<EvaluationDto.Assignment> rows = new ArrayList<>();
        for (ExamTaAssignment row : assignmentRepository
                .findByContestIdOrderByAssignmentIdAsc(eventId)) {
            EvaluationDto.Ta ta = taById.get(row.getTaUserId());
            int count = (int) paper.students().stream()
                .filter(s -> UsernameOrder.inRange(s.getUsername(), row.getRangeFrom(),
                    row.getRangeTo()))
                .count();
            rows.add(new EvaluationDto.Assignment(row.getAssignmentId(), row.getTaUserId(),
                ta == null ? null : ta.username(), ta == null ? null : ta.fullName(),
                row.getProblemLabel(), row.getRangeFrom(), row.getRangeTo(), count,
                row.getCreatedAt()));
        }
        String state = state(event, Instant.now());
        return new EvaluationDto.AssignmentBoard(state, stateMessage(state), rows, classroomTas,
            paper.problems().stream().map(EvaluationDto.Problem::label).toList(),
            paper.students().stream().map(User::getUsername).toList());
    }

    @Transactional
    public EvaluationDto.AssignmentBoard assign(Long adminId, Long eventId,
                                                EvaluationDto.AssignmentRequest req,
                                                HttpServletRequest httpReq) {
        GroupContest event = requireExam(eventId);
        if (event.isCompleted()) {
            throw ApiException.badRequest("This examination is marked done. Reopen it to hand "
                + "out more marking.");
        }
        if (!classroomRepository.isTa(event.getClassroomId(), req.taUserId())) {
            throw ApiException.badRequest("That person is not a TA in this examination's "
                + "classroom. Add them on the classroom's page first.");
        }

        String label = trimToNull(req.problemLabel());
        if (label != null) {
            label = label.toUpperCase(Locale.ROOT);
            Paper paper = load(event, false);
            if (paper.problem(label) == null) {
                throw ApiException.badRequest("This examination has no problem " + label + ".");
            }
        }
        String from = trimToNull(req.rangeFrom());
        String to = trimToNull(req.rangeTo());
        if (from != null && to != null && UsernameOrder.compare(from, to) > 0) {
            throw ApiException.badRequest("The range runs backwards: " + from + " comes after "
                + to + ".");
        }

        String wantedLabel = label;
        boolean duplicate = assignmentRepository
            .findByContestIdAndTaUserId(eventId, req.taUserId()).stream()
            .anyMatch(r -> Objects.equals(r.getProblemLabel(), wantedLabel)
                && Objects.equals(r.getRangeFrom(), from) && Objects.equals(r.getRangeTo(), to));
        if (!duplicate) {
            assignmentRepository.save(ExamTaAssignment.builder()
                .contestId(eventId)
                .taUserId(req.taUserId())
                .problemLabel(label)
                .rangeFrom(from)
                .rangeTo(to)
                .createdBy(adminId)
                .build());
            auditService.recordIn(event.getClassroomId(), adminId, AuditService.EXAM_TA_ASSIGNED,
                "EXAM", eventId + ":" + req.taUserId(), httpReq);
        }
        return board(eventId);
    }

    @Transactional
    public EvaluationDto.AssignmentBoard unassign(Long adminId, Long eventId, Long assignmentId,
                                                  HttpServletRequest httpReq) {
        GroupContest event = requireExam(eventId);
        ExamTaAssignment row = assignmentRepository.findById(assignmentId)
            .filter(r -> r.getContestId().equals(eventId))
            .orElseThrow(() -> ApiException.notFound("No such assignment on this examination."));
        assignmentRepository.delete(row);
        auditService.recordIn(event.getClassroomId(), adminId, AuditService.EXAM_TA_UNASSIGNED,
            "EXAM", eventId + ":" + row.getTaUserId(), httpReq);
        return board(eventId);
    }

    // ================================================================= shared

    private EvaluationDto.Sheet sheet(Scope scope, GroupContest event) {
        String state = state(event, Instant.now());
        // Before the end there is nothing final to mark, and a TA has no business reading
        // submissions to a paper still being sat.
        Paper paper = load(event, !NOT_ENDED.equals(state));
        List<EvaluationDto.Cell> cells = new ArrayList<>();
        if (!NOT_ENDED.equals(state)) {
            Map<Long, String> markers = markerNames(paper);
            for (User student : paper.students()) {
                for (EvaluationDto.Problem problem : paper.problems()) {
                    if (scope.covers(student, problem.label())) {
                        cells.add(cell(paper, student, problem, markers));
                    }
                }
            }
        }
        List<EvaluationDto.Problem> problems = paper.problems().stream()
            .filter(p -> scope.coversLabel(p.label()))
            .toList();
        return new EvaluationDto.Sheet(event.getContestId(), event.getName(), state,
            stateMessage(state), problems, cells);
    }

    private EvaluationDto.Submission submission(Scope scope, GroupContest event, Long userId,
                                                String rawLabel) {
        if (NOT_ENDED.equals(state(event, Instant.now()))) {
            throw ApiException.badRequest(stateMessage(NOT_ENDED));
        }
        Paper paper = load(event, true);
        Target target = target(scope, paper, userId, rawLabel);
        CodeSubmission chosen = chosen(paper.attempts(userId, target.problem().label()));
        if (chosen == null) {
            throw ApiException.notFound(target.student().getUsername() + " sent nothing to "
                + target.problem().label() + ".");
        }
        return toSubmission(chosen, true);
    }

    private EvaluationDto.Cell mark(Scope scope, GroupContest event, EvaluationDto.MarkRequest req,
                                    HttpServletRequest httpReq) {
        String state = state(event, Instant.now());
        if (!OPEN.equals(state)) throw ApiException.badRequest(stateMessage(state));

        Paper paper = load(event, true);
        Target target = target(scope, paper, req.userId(), req.label());
        EvaluationDto.Problem problem = target.problem();
        Long eventId = event.getContestId();

        ExamMark existing = markRepository.findByContestIdAndUserIdAndProblemLabel(
            eventId, req.userId(), problem.label()).orElse(null);

        if (req.marks() == null) {
            if (existing != null) {
                markRepository.delete(existing);
                paper.marks().getOrDefault(req.userId(), new HashMap<>()).remove(problem.label());
                auditService.recordIn(event.getClassroomId(), scope.viewerId(),
                    AuditService.EXAM_MARK_CLEARED, "EXAM",
                    eventId + ":" + req.userId() + ":" + problem.label(), httpReq);
            }
        } else {
            BigDecimal value = BigDecimal.valueOf(req.marks()).setScale(2, RoundingMode.HALF_UP);
            if (value.doubleValue() > problem.maxMarks()) {
                throw ApiException.badRequest(problem.label() + " is worth "
                    + number(problem.maxMarks()) + " marks"
                    + (problem.maxMarks() == 0 ? ". Set its marks on the Problems tab first." : "."));
            }
            CodeSubmission chosen = chosen(paper.attempts(req.userId(), problem.label()));
            ExamMark row = existing != null ? existing : ExamMark.builder()
                .contestId(eventId).userId(req.userId()).problemLabel(problem.label()).build();
            row.setMarks(value);
            row.setRemark(trimToNull(req.remark()));
            row.setSubmissionId(chosen == null ? null : chosen.getId());
            row.setMarkedBy(scope.viewerId());
            row.setMarkedAt(Instant.now());
            markRepository.save(row);
            paper.marks().computeIfAbsent(req.userId(), k -> new HashMap<>())
                .put(problem.label(), row);
            auditService.recordIn(event.getClassroomId(), scope.viewerId(),
                AuditService.EXAM_MARK_SET, "EXAM",
                eventId + ":" + req.userId() + ":" + problem.label() + "=" + value.toPlainString(),
                httpReq);
        }

        // The stored board was ranked on the old mark; drop it so the next read re-ranks.
        eventRepository.storeLeaderboard(eventId, null, null);
        return cell(paper, target.student(), problem, markerNames(paper));
    }

    private Target target(Scope scope, Paper paper, Long userId, String rawLabel) {
        String label = rawLabel == null ? null : rawLabel.trim().toUpperCase(Locale.ROOT);
        EvaluationDto.Problem problem = label == null ? null : paper.problem(label);
        User student = paper.student(userId);
        // One answer for "no such cell" and "not yours to mark", so an id probes nothing.
        if (problem == null || student == null || !scope.covers(student, label)) {
            throw ApiException.notFound("That isn't one of the answers you mark.");
        }
        return new Target(student, problem);
    }

    private EvaluationDto.Cell cell(Paper paper, User student, EvaluationDto.Problem problem,
                                    Map<Long, String> markers) {
        List<CodeSubmission> attempts = paper.attempts(student.getUserId(), problem.label());
        CodeSubmission chosen = chosen(attempts);
        boolean solved = attempts.stream().anyMatch(a -> "OK".equals(a.getVerdict()));
        ExamMark mark = paper.mark(student.getUserId(), problem.label());
        return new EvaluationDto.Cell(student.getUserId(), student.getUsername(),
            student.getFullName(), problem.label(), problem.maxMarks(),
            solved ? problem.maxMarks() : 0,
            mark == null ? null : mark.getMarks().doubleValue(),
            mark == null ? null : mark.getRemark(),
            mark == null ? null : markers.get(mark.getMarkedBy()),
            mark == null ? null : mark.getMarkedAt(),
            chosen == null ? null : toSubmission(chosen, false),
            attempts.size());
    }

    /** The latest accepted attempt, or the latest of all when none was accepted. */
    static CodeSubmission chosen(List<CodeSubmission> attempts) {
        Comparator<CodeSubmission> byTime = Comparator.comparing(CodeSubmission::getSubmittedAt,
            Comparator.nullsFirst(Comparator.naturalOrder()));
        return attempts.stream().filter(a -> "OK".equals(a.getVerdict())).max(byTime)
            .orElseGet(() -> attempts.stream().max(byTime).orElse(null));
    }

    private static EvaluationDto.Submission toSubmission(CodeSubmission row, boolean withSource) {
        return new EvaluationDto.Submission(row.getId(), row.getVerdict(),
            "OK".equals(row.getVerdict()), row.getSubmittedAt(), row.getLanguageLabel(),
            row.getSourceBytes(), withSource ? row.getSource() : null);
    }

    private Map<Long, String> markerNames(Paper paper) {
        Set<Long> ids = new HashSet<>();
        paper.marks().values().forEach(m -> m.values().forEach(r -> {
            if (r.getMarkedBy() != null) ids.add(r.getMarkedBy());
        }));
        Map<Long, String> out = new HashMap<>();
        for (User user : userRepository.findAllById(ids)) out.put(user.getUserId(), user.getUsername());
        return out;
    }

    // ------------------------------------------------------------------ scope

    private Scope taScope(Long taId, GroupContest event) {
        // The same answer for "no such paper" and "not yours", as for candidates.
        if (!event.isExam() || !classroomRepository.isTa(event.getClassroomId(), taId)) {
            throw ApiException.notFound("No such examination");
        }
        List<ExamTaAssignment> rows =
            assignmentRepository.findByContestIdAndTaUserId(event.getContestId(), taId);
        if (rows.isEmpty()) throw ApiException.notFound("No such examination");
        return new Scope(taId, false, rows);
    }

    /** What one marker may mark: everything, or what their assignments cover. */
    record Scope(Long viewerId, boolean all, List<ExamTaAssignment> rows) {

        static Scope everything(Long adminId) {
            return new Scope(adminId, true, List.of());
        }

        boolean covers(User student, String label) {
            if (student.getUserId().equals(viewerId)) return false;
            if (all) return true;
            for (ExamTaAssignment row : rows) {
                if ((row.getProblemLabel() == null || row.getProblemLabel().equalsIgnoreCase(label))
                        && UsernameOrder.inRange(student.getUsername(), row.getRangeFrom(),
                            row.getRangeTo())) {
                    return true;
                }
            }
            return false;
        }

        boolean coversLabel(String label) {
            return all || rows.stream().anyMatch(r -> r.getProblemLabel() == null
                || r.getProblemLabel().equalsIgnoreCase(label));
        }
    }

    // ------------------------------------------------------------------ state

    static String state(GroupContest event, Instant now) {
        if (event.isCompleted()) return DONE;
        if (!event.hasStarted(now) || event.getEndsAt() == null
                || now.isBefore(event.getEndsAt())) {
            return NOT_ENDED;
        }
        return OPEN;
    }

    static String stateMessage(String state) {
        return switch (state) {
            case NOT_ENDED -> "Marking opens when the examination ends.";
            case DONE -> "This examination is marked done, so its marks are final. An admin "
                + "can reopen it to change them.";
            default -> null;
        };
    }

    private GroupContest requireExam(Long eventId) {
        GroupContest event = events.require(eventId);
        if (!event.isExam()) throw ApiException.badRequest("Only examinations are marked by hand.");
        return event;
    }

    // ------------------------------------------------------------------ paper

    /**
     * What a sheet is built from: the questions with what each is worth, the roster in natural
     * order, the marks set so far and, when asked for, every attempt during the paper.
     */
    private Paper load(GroupContest event, boolean withAttempts) {
        Long eventId = event.getContestId();
        List<ContestProblem> listed =
            problemRepository.findByContestContestIdOrderByOrderingAscLabelAsc(eventId);
        List<CodeSubmission> rows = withAttempts ? boards.attemptsOf(event) : List.of();

        boolean anyMarks = listed.stream().anyMatch(p -> p.getPoints() != null);
        List<EvaluationDto.Problem> problems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ContestProblem p : listed) {
            if (p.getLabel() == null || p.getLabel().isBlank()) continue;
            String label = p.getLabel().toUpperCase(Locale.ROOT);
            if (!seen.add(label)) continue;
            // As the leaderboard counts it: with no marks set anywhere every problem is worth
            // one; once any are set, one left blank is worth nothing.
            double max = anyMarks ? (p.getPoints() == null ? 0 : p.getPoints().doubleValue()) : 1;
            problems.add(new EvaluationDto.Problem(label, p.getTitle(), max));
        }
        if (problems.isEmpty()) {
            // Nothing listed: the leaderboard ranks on whatever was submitted to, so this does.
            Set<String> labels = new TreeSet<>();
            for (CodeSubmission row : withAttempts ? rows : boards.attemptsOf(event)) {
                if (row.getProblemIndex() != null) {
                    labels.add(row.getProblemIndex().toUpperCase(Locale.ROOT));
                }
            }
            for (String label : labels) problems.add(new EvaluationDto.Problem(label, null, 1));
        }

        List<User> students = new ArrayList<>(
            userRepository.findAllById(events.participantIds(eventId)));
        students.sort(Comparator.comparing(User::getUsername, UsernameOrder.NATURAL));

        Map<Long, Map<String, List<CodeSubmission>>> attempts = new HashMap<>();
        for (CodeSubmission row : rows) {
            if (row.getProblemIndex() == null) continue;
            attempts.computeIfAbsent(row.getUserId(), k -> new HashMap<>())
                .computeIfAbsent(row.getProblemIndex().toUpperCase(Locale.ROOT),
                    k -> new ArrayList<>())
                .add(row);
        }

        Map<Long, Map<String, ExamMark>> marks = new HashMap<>();
        for (ExamMark mark : markRepository.findByContestId(eventId)) {
            marks.computeIfAbsent(mark.getUserId(), k -> new HashMap<>())
                .put(mark.getProblemLabel(), mark);
        }
        return new Paper(problems, students, attempts, marks);
    }

    private record Paper(List<EvaluationDto.Problem> problems, List<User> students,
                         Map<Long, Map<String, List<CodeSubmission>>> attemptsByUser,
                         Map<Long, Map<String, ExamMark>> marks) {

        EvaluationDto.Problem problem(String label) {
            return problems.stream().filter(p -> p.label().equals(label)).findFirst().orElse(null);
        }

        User student(Long userId) {
            return userId == null ? null
                : students.stream().filter(s -> s.getUserId().equals(userId)).findFirst()
                    .orElse(null);
        }

        List<CodeSubmission> attempts(Long userId, String label) {
            return attemptsByUser.getOrDefault(userId, Map.of()).getOrDefault(label, List.of());
        }

        ExamMark mark(Long userId, String label) {
            return marks.getOrDefault(userId, Map.of()).get(label);
        }
    }

    private record Target(User student, EvaluationDto.Problem problem) {}

    private static String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
