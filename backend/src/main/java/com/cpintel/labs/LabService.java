package com.cpintel.labs;

import com.cpintel.classrooms.ClassroomService;
import com.cpintel.common.Xlsx;
import com.cpintel.entity.Classroom;
import com.cpintel.entity.ContestGroup;
import com.cpintel.entity.LabAssignment;
import com.cpintel.entity.LabProblem;
import com.cpintel.entity.LabSolve;
import com.cpintel.entity.PlatformAccount;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.integration.codeforces.CfModels;
import com.cpintel.integration.codeforces.CfProblemsetClient;
import com.cpintel.integration.leetcode.LeetCodeClient;
import com.cpintel.repository.jpa.ClassroomMemberRepository;
import com.cpintel.repository.jpa.ClassroomRepository;
import com.cpintel.repository.jpa.ContestGroupRepository;
import com.cpintel.repository.jpa.LabAssignmentRepository;
import com.cpintel.repository.jpa.LabSolveRepository;
import com.cpintel.repository.jpa.PlatformAccountRepository;
import com.cpintel.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Lab assignments: setting them, and reading where everyone is with them.
 *
 * <p>An admin who runs the classroom sets a lab's problems by pasting links; each is looked up
 * on its platform as it is added, so a typo is caught then rather than when nobody's solve of
 * it ever appears. Students see a lab once it opens. Marks are earned by solves in time; late
 * solves are shown and worth nothing. The solves themselves are found by {@link LabCheckService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LabService {

    private static final DateTimeFormatter IST =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'IST'").withZone(ZoneId.of("Asia/Kolkata"));

    private final LabAssignmentRepository labs;
    private final LabSolveRepository solves;
    private final PlatformAccountRepository accounts;
    private final ClassroomMemberRepository members;
    private final ClassroomRepository classrooms;
    private final ContestGroupRepository groups;
    private final ClassroomService classroomService;
    private final LabAudience audience;
    private final LabCheckService checker;
    private final CfProblemsetClient problemset;
    private final LeetCodeClient leetCode;
    private final AuditService auditService;

    // ------------------------------------------------------------------ problems

    /** Looks a pasted problem up on its platform: what the admin sees before saving. */
    public LabsDto.ProblemView resolve(String link) {
        ProblemRef ref = ProblemRef.parse(link);
        LabProblem p = lookUp(ref, BigDecimal.ONE);
        return view(p);
    }

    private LabProblem lookUp(ProblemRef ref, BigDecimal marks) {
        String title;
        String difficulty = null;
        if (ProblemRef.LEETCODE.equals(ref.platform())) {
            LeetCodeClient.Problem found;
            try {
                found = leetCode.problem(ref.key());
            } catch (Exception e) {
                log.warn("LeetCode lookup of {} failed: {}", ref.key(), e.getMessage());
                throw ApiException.serviceUnavailable("LeetCode did not answer. Try again in a minute.");
            }
            if (found == null) throw ApiException.badRequest("LeetCode has no problem \"" + ref.key() + "\".");
            title = (found.frontendId() != null ? found.frontendId() + ". " : "") + found.title()
                + (found.paidOnly() ? " (Premium)" : "");
            difficulty = found.difficulty();
        } else if (ref.isGym()) {
            // Gym problems are not in the public problemset; the link is taken as given.
            title = "Gym " + ref.cfContestId() + " · problem " + ref.cfIndex();
        } else {
            List<CfModels.Submission.Problem> all;
            try {
                all = problemset.getAllProblems();
            } catch (Exception e) {
                log.warn("Codeforces problemset lookup failed: {}", e.getMessage());
                throw ApiException.serviceUnavailable("Codeforces did not answer. Try again in a minute.");
            }
            CfModels.Submission.Problem found = all.stream()
                .filter(p -> Integer.valueOf(ref.cfContestId()).equals(p.getContestId())
                    && ref.cfIndex().equalsIgnoreCase(p.getIndex()))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("Codeforces has no problem "
                    + ref.cfContestId() + ref.cfIndex() + " in its problemset."));
            title = ref.cfContestId() + ref.cfIndex() + ". " + found.getName();
            difficulty = found.getRating() == null ? null : String.valueOf(found.getRating());
        }
        return LabProblem.builder()
            .platform(ref.platform())
            .problemKey(ref.key())
            .title(truncate(title, 300))
            .url(ref.url())
            .difficulty(difficulty)
            .marks(marks == null ? BigDecimal.ONE : marks)
            .build();
    }

    // ------------------------------------------------------------------ admin

    @Transactional(readOnly = true)
    public List<LabsDto.LabSummary> list(Long classroomId) {
        Map<Long, String> groupNames = groupNames(classroomId);
        return labs.findByClassroomIdOrderByDueAtDesc(classroomId).stream()
            .map(l -> summarise(l, groupNames, audience.students(l).size()))
            .toList();
    }

    @Transactional(readOnly = true)
    public LabsDto.LabSummary detail(Long classroomId, Long labId) {
        LabAssignment lab = require(classroomId, labId);
        return summarise(lab, groupNames(classroomId), audience.students(lab).size());
    }

    @Transactional
    public LabsDto.LabSummary create(Long adminId, Long classroomId, LabsDto.LabRequest req,
                                     HttpServletRequest httpReq) {
        validate(classroomId, req);
        LabAssignment lab = LabAssignment.builder()
            .classroomId(classroomId)
            .createdBy(adminId)
            .build();
        apply(lab, req);
        lab.setProblems(new ArrayList<>());
        int order = 0;
        for (LabProblem p : resolveAll(req.problems())) {
            p.setLab(lab);
            p.setOrdering(order++);
            lab.getProblems().add(p);
        }
        lab = labs.save(lab);
        auditService.recordIn(classroomId, adminId, AuditService.LAB_CREATED, "LAB",
            lab.getLabId() + ":" + lab.getTitle(), httpReq);
        Long id = lab.getLabId();
        afterCommit(() -> checker.checkLabInBackground(id));
        return summarise(lab, groupNames(classroomId), audience.students(lab).size());
    }

    /**
     * Changes a lab. Problems that stay keep their solves; a changed window, or a change to
     * whether earlier solves count, throws every solve away to be found again under the new
     * rules, since a solve recorded under the old ones may no longer qualify.
     */
    @Transactional
    public LabsDto.LabSummary update(Long adminId, Long classroomId, Long labId,
                                     LabsDto.LabRequest req, HttpServletRequest httpReq) {
        validate(classroomId, req);
        LabAssignment lab = require(classroomId, labId);
        boolean rulesChanged = !lab.getOpensAt().equals(req.opensAt())
            || !lab.getDueAt().equals(req.dueAt())
            || lab.getCountEarlierSolves() != req.countEarlierSolves();
        apply(lab, req);

        Map<String, LabProblem> existing = new HashMap<>();
        for (LabProblem p : lab.getProblems()) existing.put(p.getPlatform() + ":" + p.getProblemKey(), p);

        List<LabProblem> next = new ArrayList<>();
        List<LabsDto.ProblemInput> fresh = new ArrayList<>();
        List<ProblemRef> refs = refs(req.problems());
        for (int i = 0; i < refs.size(); i++) {
            LabProblem kept = existing.get(refs.get(i).platform() + ":" + refs.get(i).key());
            if (kept != null) {
                kept.setMarks(marks(req.problems().get(i)));
                next.add(kept);
            } else {
                next.add(null);
                fresh.add(req.problems().get(i));
            }
        }
        List<LabProblem> looked = resolveAll(fresh);
        int f = 0;
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i) == null) {
                LabProblem p = looked.get(f++);
                p.setLab(lab);
                next.set(i, p);
            }
            next.get(i).setOrdering(i);
        }
        lab.getProblems().retainAll(next);
        for (LabProblem p : next) if (!lab.getProblems().contains(p)) lab.getProblems().add(p);
        lab.getProblems().sort((a, b) -> Integer.compare(a.getOrdering(), b.getOrdering()));

        if (rulesChanged) {
            List<Long> ids = lab.getProblems().stream().map(LabProblem::getLabProblemId)
                .filter(Objects::nonNull).toList();
            if (!ids.isEmpty()) solves.deleteByLabProblemIdIn(ids);
            lab.setLastCheckedAt(null);
        }
        lab = labs.save(lab);
        auditService.recordIn(classroomId, adminId, AuditService.LAB_UPDATED, "LAB",
            labId + ":" + lab.getTitle(), httpReq);
        afterCommit(() -> checker.checkLabInBackground(labId));
        return summarise(lab, groupNames(classroomId), audience.students(lab).size());
    }

    @Transactional
    public void delete(Long adminId, Long classroomId, Long labId, HttpServletRequest httpReq) {
        LabAssignment lab = require(classroomId, labId);
        labs.delete(lab);
        auditService.recordIn(classroomId, adminId, AuditService.LAB_DELETED, "LAB",
            labId + ":" + lab.getTitle(), httpReq);
    }

    public LabsDto.CheckStarted checkNow(Long classroomId, Long labId) {
        LabAssignment lab = require(classroomId, labId);
        if (lab.getOpensAt().isAfter(Instant.now())) {
            return new LabsDto.CheckStarted(false, "This lab has not opened yet.");
        }
        boolean started = checker.checkLabInBackground(labId);
        return new LabsDto.CheckStarted(started, started
            ? "Checking everyone's submissions. This takes a few seconds per student."
            : "A check of this lab is already running.");
    }

    @Transactional(readOnly = true)
    public LabsDto.Progress progress(Long classroomId, Long labId) {
        LabAssignment lab = require(classroomId, labId);
        List<User> students = audience.students(lab);
        LabsDto.LabSummary summary = summarise(lab, groupNames(classroomId), students.size());
        if (students.isEmpty()) return new LabsDto.Progress(summary, List.of());

        Map<String, LabSolve> solveOf = solvesOf(lab);
        Map<Long, Map<String, PlatformAccount>> handles = handlesOf(students);

        List<LabsDto.StudentRow> rows = new ArrayList<>();
        for (User u : students) {
            List<LabsDto.Cell> cells = new ArrayList<>();
            int solved = 0, late = 0;
            BigDecimal marks = BigDecimal.ZERO;
            for (LabProblem p : lab.getProblems()) {
                LabSolve s = solveOf.get(p.getLabProblemId() + ":" + u.getUserId());
                if (s == null) {
                    cells.add(new LabsDto.Cell(p.getLabProblemId(), null, null));
                } else if (Boolean.TRUE.equals(s.getLate())) {
                    late++;
                    cells.add(new LabsDto.Cell(p.getLabProblemId(), "LATE", s.getSolvedAt()));
                } else {
                    solved++;
                    marks = marks.add(p.getMarks());
                    cells.add(new LabsDto.Cell(p.getLabProblemId(), "SOLVED", s.getSolvedAt()));
                }
            }
            Map<String, PlatformAccount> mine = handles.getOrDefault(u.getUserId(), Map.of());
            PlatformAccount cf = mine.get(ProblemRef.CODEFORCES);
            PlatformAccount lc = mine.get(ProblemRef.LEETCODE);
            rows.add(new LabsDto.StudentRow(u.getUserId(), u.getUsername(), u.getFullName(),
                cf == null ? null : cf.getHandle(), cf != null && cf.getVerifiedAt() != null,
                lc == null ? null : lc.getHandle(), lc != null && lc.getVerifiedAt() != null,
                cells, solved, late, marks));
        }
        return new LabsDto.Progress(summary, rows);
    }

    public record Workbook(String fileName, byte[] bytes) {}

    @Transactional(readOnly = true)
    public Workbook workbook(Long classroomId, Long labId) {
        LabsDto.Progress progress = progress(classroomId, labId);
        LabsDto.LabSummary lab = progress.lab();

        List<List<Object>> marks = new ArrayList<>();
        List<Object> header = new ArrayList<>(List.of("Username", "Full name", "Codeforces", "LeetCode"));
        for (LabsDto.ProblemView p : lab.problems()) header.add(p.title() + " (" + p.marks().stripTrailingZeros().toPlainString() + ")");
        header.addAll(List.of("Solved in time", "Solved late", "Marks", "Out of"));
        marks.add(header);

        List<List<Object>> detail = new ArrayList<>();
        detail.add(List.of("Username", "Problem", "Platform", "Accepted at", "Late", "Handle"));
        Map<Long, LabsDto.ProblemView> problemById = new HashMap<>();
        for (LabsDto.ProblemView p : lab.problems()) problemById.put(p.problemId(), p);

        for (LabsDto.StudentRow r : progress.students()) {
            List<Object> row = new ArrayList<>();
            row.add(r.username());
            row.add(r.fullName() == null ? "" : r.fullName());
            row.add(handleCell(r.codeforcesHandle(), r.codeforcesVerified()));
            row.add(handleCell(r.leetcodeHandle(), r.leetcodeVerified()));
            for (LabsDto.Cell c : r.cells()) {
                LabsDto.ProblemView p = problemById.get(c.problemId());
                if ("SOLVED".equals(c.status())) row.add(p.marks());
                else if ("LATE".equals(c.status())) row.add("late");
                else row.add("");
                if (c.status() != null) {
                    detail.add(List.of(r.username(), p.title(), p.platform(), IST.format(c.solvedAt()),
                        "LATE".equals(c.status()) ? "yes" : "no",
                        ProblemRef.LEETCODE.equals(p.platform())
                            ? Optional.ofNullable(r.leetcodeHandle()).orElse("")
                            : Optional.ofNullable(r.codeforcesHandle()).orElse("")));
                }
            }
            row.add(r.solved());
            row.add(r.late());
            row.add(r.marks());
            row.add(lab.totalMarks());
            marks.add(row);
        }

        List<List<Object>> about = List.of(
            List.of("Lab", lab.title()),
            List.of("Opens", IST.format(lab.opensAt())),
            List.of("Due", IST.format(lab.dueAt())),
            List.of("Earlier solves count", lab.countEarlierSolves() ? "yes" : "no"),
            List.of("Last checked", lab.lastCheckedAt() == null ? "never" : IST.format(lab.lastCheckedAt())),
            List.of("Note", "Only solves from verified handles are credited. Late solves earn no marks."));

        byte[] bytes = new Xlsx().sheet("Marks", marks).sheet("Solves", detail).sheet("Lab", about).toBytes();
        String name = lab.title().replaceAll("[^A-Za-z0-9._ -]", "").trim().replace(' ', '_');
        return new Workbook((name.isEmpty() ? "lab-" + labId : name) + ".xlsx", bytes);
    }

    private static String handleCell(String handle, boolean verified) {
        if (handle == null) return "";
        return verified ? handle : handle + " (unverified)";
    }

    // ------------------------------------------------------------------ student

    @Transactional(readOnly = true)
    public LabsDto.MyLabs mine(Long userId) {
        Instant now = Instant.now();
        Map<String, PlatformAccount> handles = new HashMap<>();
        for (PlatformAccount a : accounts.findByUserUserId(userId)) handles.put(a.getPlatform(), a);

        List<Long> classroomIds = members.classroomIdsOf(userId);
        List<LabsDto.MyLab> out = new ArrayList<>();
        if (!classroomIds.isEmpty()) {
            Map<Long, String> classroomNames = new HashMap<>();
            for (Classroom c : classrooms.findAllById(classroomIds)) {
                if (Boolean.TRUE.equals(c.getIsActive())) classroomNames.put(c.getClassroomId(), c.getName());
            }
            List<LabAssignment> visible = labs.findOpenedIn(classroomIds, now).stream()
                .filter(l -> classroomNames.containsKey(l.getClassroomId()))
                .filter(l -> audience.includes(l, userId))
                .toList();
            List<Long> problemIds = visible.stream().flatMap(l -> l.getProblems().stream())
                .map(LabProblem::getLabProblemId).toList();
            Map<Long, LabSolve> solveOf = new HashMap<>();
            if (!problemIds.isEmpty()) {
                for (LabSolve s : solves.findByUserIdAndLabProblemIdIn(userId, problemIds)) {
                    solveOf.put(s.getLabProblemId(), s);
                }
            }
            for (LabAssignment l : visible) {
                List<LabsDto.MyProblem> problems = new ArrayList<>();
                BigDecimal marks = BigDecimal.ZERO;
                for (LabProblem p : l.getProblems()) {
                    LabSolve s = solveOf.get(p.getLabProblemId());
                    String status = s == null ? null : Boolean.TRUE.equals(s.getLate()) ? "LATE" : "SOLVED";
                    if ("SOLVED".equals(status)) marks = marks.add(p.getMarks());
                    problems.add(new LabsDto.MyProblem(view(p), status, s == null ? null : s.getSolvedAt()));
                }
                out.add(new LabsDto.MyLab(l.getLabId(), l.getClassroomId(),
                    classroomNames.get(l.getClassroomId()), l.getTitle(), l.getDescription(),
                    l.getOpensAt(), l.getDueAt(), Boolean.TRUE.equals(l.getCountEarlierSolves()),
                    l.getLastCheckedAt(), state(l, now), problems, marks, total(l)));
            }
        }
        return new LabsDto.MyLabs(out, handle(handles.get(ProblemRef.CODEFORCES)),
            handle(handles.get(ProblemRef.LEETCODE)));
    }

    private static LabsDto.Handle handle(PlatformAccount a) {
        return a == null ? null : new LabsDto.Handle(a.getHandle(), a.getVerifiedAt() != null);
    }

    // ------------------------------------------------------------------ helpers

    private LabAssignment require(Long classroomId, Long labId) {
        return labs.findById(labId)
            .filter(l -> l.getClassroomId().equals(classroomId))
            .orElseThrow(() -> ApiException.notFound("No lab " + labId + " in this classroom."));
    }

    private void validate(Long classroomId, LabsDto.LabRequest req) {
        classroomService.require(classroomId);
        if (!req.dueAt().isAfter(req.opensAt())) {
            throw ApiException.badRequest("The deadline has to be after the lab opens.");
        }
        if (req.groupIds() != null) {
            for (Long g : req.groupIds()) {
                ContestGroup group = groups.findById(g)
                    .orElseThrow(() -> ApiException.badRequest("No team with id " + g + "."));
                if (!classroomId.equals(group.getClassroomId())) {
                    throw ApiException.badRequest("Team \"" + group.getName() + "\" is not in this classroom.");
                }
            }
        }
        refs(req.problems());
    }

    /** The problems' refs, refusing the same problem twice. */
    private static List<ProblemRef> refs(List<LabsDto.ProblemInput> inputs) {
        List<ProblemRef> refs = new ArrayList<>();
        Set<ProblemRef> seen = new HashSet<>();
        for (LabsDto.ProblemInput in : inputs) {
            ProblemRef ref = ProblemRef.parse(in.link());
            if (!seen.add(ref)) throw ApiException.badRequest("\"" + in.link() + "\" is in the lab twice.");
            refs.add(ref);
        }
        return refs;
    }

    private List<LabProblem> resolveAll(List<LabsDto.ProblemInput> inputs) {
        List<LabProblem> out = new ArrayList<>();
        for (LabsDto.ProblemInput in : inputs) out.add(lookUp(ProblemRef.parse(in.link()), marks(in)));
        return out;
    }

    private static BigDecimal marks(LabsDto.ProblemInput in) {
        return in.marks() == null ? BigDecimal.ONE : in.marks();
    }

    private static void apply(LabAssignment lab, LabsDto.LabRequest req) {
        lab.setTitle(req.title().trim());
        lab.setDescription(StringUtils.hasText(req.description()) ? req.description().trim() : null);
        lab.setOpensAt(req.opensAt());
        lab.setDueAt(req.dueAt());
        lab.setCountEarlierSolves(req.countEarlierSolves());
        Set<Long> groupIds = lab.getGroupIds() == null ? new HashSet<>() : lab.getGroupIds();
        groupIds.clear();
        if (req.groupIds() != null) groupIds.addAll(req.groupIds());
        lab.setGroupIds(groupIds);
    }

    private Map<Long, String> groupNames(Long classroomId) {
        Map<Long, String> names = new LinkedHashMap<>();
        for (ContestGroup g : groups.findByClassroomIdOrderByName(classroomId)) {
            names.put(g.getGroupId(), g.getName());
        }
        return names;
    }

    private Map<String, LabSolve> solvesOf(LabAssignment lab) {
        List<Long> ids = lab.getProblems().stream().map(LabProblem::getLabProblemId).toList();
        Map<String, LabSolve> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        for (LabSolve s : solves.findByLabProblemIdIn(ids)) out.put(s.getLabProblemId() + ":" + s.getUserId(), s);
        return out;
    }

    private Map<Long, Map<String, PlatformAccount>> handlesOf(List<User> students) {
        Map<Long, Map<String, PlatformAccount>> out = new HashMap<>();
        List<Long> ids = students.stream().map(User::getUserId).toList();
        for (PlatformAccount a : accounts.findByUserUserIdIn(ids)) {
            out.computeIfAbsent(a.getUser().getUserId(), k -> new HashMap<>()).put(a.getPlatform(), a);
        }
        return out;
    }

    private LabsDto.LabSummary summarise(LabAssignment l, Map<Long, String> groupNames, int studentCount) {
        List<Long> groupIds = l.getGroupIds() == null ? List.of() : l.getGroupIds().stream().sorted().toList();
        return new LabsDto.LabSummary(l.getLabId(), l.getClassroomId(), l.getTitle(), l.getDescription(),
            l.getOpensAt(), l.getDueAt(), Boolean.TRUE.equals(l.getCountEarlierSolves()), groupIds,
            groupIds.stream().map(id -> groupNames.getOrDefault(id, "Team " + id)).toList(),
            l.getLastCheckedAt(), l.getProblems().stream().map(LabService::view).toList(),
            total(l), studentCount, state(l, Instant.now()));
    }

    private static BigDecimal total(LabAssignment l) {
        return l.getProblems().stream().map(LabProblem::getMarks).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static String state(LabAssignment l, Instant now) {
        if (now.isBefore(l.getOpensAt())) return "UPCOMING";
        return now.isAfter(l.getDueAt()) ? "CLOSED" : "OPEN";
    }

    private static LabsDto.ProblemView view(LabProblem p) {
        return new LabsDto.ProblemView(p.getLabProblemId(), p.getPlatform(), p.getProblemKey(),
            p.getTitle(), p.getUrl(), p.getDifficulty(), p.getMarks());
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Runs once the transaction has committed, so a background check sees what was saved. */
    private static void afterCommit(Runnable action) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                });
        } else {
            action.run();
        }
    }
}
