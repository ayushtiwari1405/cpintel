package com.cpintel.labs;

import com.cpintel.entity.LabAssignment;
import com.cpintel.entity.LabProblem;
import com.cpintel.entity.LabSolve;
import com.cpintel.entity.PlatformAccount;
import com.cpintel.entity.User;
import com.cpintel.integration.codeforces.CfModels;
import com.cpintel.integration.codeforces.CodeforcesClient;
import com.cpintel.integration.leetcode.LeetCodeClient;
import com.cpintel.repository.jpa.ClassroomMemberRepository;
import com.cpintel.repository.jpa.LabAssignmentRepository;
import com.cpintel.repository.jpa.LabSolveRepository;
import com.cpintel.repository.jpa.PlatformAccountRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reads students' accepted submissions on Codeforces and LeetCode and records which lab
 * problems they solve.
 *
 * <p>Only verified handles are read: a lab credits the student whose account it is, so a handle
 * nobody has proven earns nothing. Only problems a student has not yet solved in time are
 * looked for, so a student who has finished a lab costs no calls at all.
 *
 * <p><b>Codeforces</b> is read newest first, a page at a time, back to the earliest moment any
 * of the student's open labs could count from: the lab's opening, or the beginning when the lab
 * counts earlier solves. <b>LeetCode</b> only ever shows the last twenty accepted submissions,
 * so it is read on every check; the schedule runs often enough that a student cannot solve
 * twenty problems in between.
 */
@Service
@Slf4j
public class LabCheckService {

    /** Late solves go on being recorded for this long after the deadline. */
    public static final Duration LATE_WINDOW = Duration.ofDays(7);
    static final int CF_PAGE = 500;
    static final int CF_MAX_PAGES = 20;

    private final LabAssignmentRepository labs;
    private final LabSolveRepository solves;
    private final PlatformAccountRepository accounts;
    private final ClassroomMemberRepository members;
    private final LabAudience audience;
    private final CodeforcesClient codeforces;
    private final LeetCodeClient leetCode;
    private final com.cpintel.integration.cses.CsesClient cses;
    private final com.cpintel.service.CsesAccountService csesAccounts;
    private final TransactionTemplate transaction;

    /** One background check at a time; the outbound limiter paces the calls anyway. */
    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lab-check");
        t.setDaemon(true);
        return t;
    });
    private final Set<Long> queued = ConcurrentHashMap.newKeySet();

    public LabCheckService(LabAssignmentRepository labs, LabSolveRepository solves,
                           PlatformAccountRepository accounts, ClassroomMemberRepository members,
                           LabAudience audience, CodeforcesClient codeforces,
                           LeetCodeClient leetCode, com.cpintel.integration.cses.CsesClient cses,
                           com.cpintel.service.CsesAccountService csesAccounts,
                           PlatformTransactionManager transactions) {
        this.labs = labs;
        this.solves = solves;
        this.accounts = accounts;
        this.members = members;
        this.audience = audience;
        this.codeforces = codeforces;
        this.leetCode = leetCode;
        this.cses = cses;
        this.csesAccounts = csesAccounts;
        this.transaction = new TransactionTemplate(transactions);
    }

    @PreDestroy
    void stop() {
        background.shutdownNow();
    }

    /** Every lab still worth checking; what the schedule runs. */
    public int checkAll() {
        Instant now = Instant.now();
        return run(labs.findCheckable(now, now.minus(LATE_WINDOW)), null);
    }

    /** One student's labs, now; what their Check button runs. */
    public int checkStudent(Long userId) {
        Instant now = Instant.now();
        List<Long> classrooms = members.classroomIdsOf(userId);
        if (classrooms.isEmpty()) return 0;
        List<LabAssignment> mine = labs.findOpenedIn(classrooms, now).stream()
            .filter(l -> !l.getDueAt().isBefore(now.minus(LATE_WINDOW)))
            .filter(l -> audience.includes(l, userId))
            .toList();
        return run(mine, userId);
    }

    /**
     * Queues a check of one lab for everyone in it. A lab of two hundred is minutes of paced
     * calls, longer than a request should wait, so it runs in the background.
     *
     * @return false when that lab is already queued
     */
    public boolean checkLabInBackground(Long labId) {
        if (!queued.add(labId)) return false;
        background.submit(() -> {
            try {
                labs.findWithProblems(labId).ifPresent(lab -> {
                    if (!lab.getOpensAt().isAfter(Instant.now())) run(List.of(lab), null);
                });
            } catch (Exception e) {
                log.warn("Background check of lab {} failed: {}", labId, e.getMessage());
            } finally {
                queued.remove(labId);
            }
        });
        return true;
    }

    /** One problem of one lab, for one student. */
    private record Target(LabAssignment lab, LabProblem problem) {}

    /**
     * Checks these labs, for everyone in them or only {@code onlyUser}.
     *
     * @return how many solves were recorded or turned from late to in time
     */
    int run(List<LabAssignment> toCheck, Long onlyUser) {
        if (toCheck.isEmpty()) return 0;

        // Who needs what. A problem already solved in time is never looked for again.
        List<Long> problemIds = toCheck.stream()
            .flatMap(l -> l.getProblems().stream()).map(LabProblem::getLabProblemId).toList();
        Set<String> doneInTime = new HashSet<>();
        for (LabSolve s : onlyUser == null
                ? solves.findByLabProblemIdIn(problemIds)
                : solves.findByUserIdAndLabProblemIdIn(onlyUser, problemIds)) {
            if (!Boolean.TRUE.equals(s.getLate())) doneInTime.add(s.getLabProblemId() + ":" + s.getUserId());
        }

        Map<Long, List<Target>> pendingByUser = new LinkedHashMap<>();
        for (LabAssignment lab : toCheck) {
            List<Long> users = onlyUser != null ? List.of(onlyUser)
                : audience.students(lab).stream().map(User::getUserId).toList();
            for (Long userId : users) {
                for (LabProblem p : lab.getProblems()) {
                    if (doneInTime.contains(p.getLabProblemId() + ":" + userId)) continue;
                    pendingByUser.computeIfAbsent(userId, k -> new ArrayList<>()).add(new Target(lab, p));
                }
            }
        }

        Map<Long, List<PlatformAccount>> handles = new HashMap<>();
        if (!pendingByUser.isEmpty()) {
            for (PlatformAccount a : accounts.findByUserUserIdIn(pendingByUser.keySet())) {
                handles.computeIfAbsent(a.getUser().getUserId(), k -> new ArrayList<>()).add(a);
            }
        }

        int recorded = 0;
        for (var entry : pendingByUser.entrySet()) {
            Long userId = entry.getKey();
            for (PlatformAccount account : handles.getOrDefault(userId, List.of())) {
                if (account.getVerifiedAt() == null) continue;
                List<Target> targets = entry.getValue().stream()
                    .filter(t -> t.problem().getPlatform().equals(account.getPlatform())).toList();
                if (targets.isEmpty()) continue;
                try {
                    recorded += checkOne(userId, account, targets);
                } catch (Exception e) {
                    // One unreadable handle must not stop everybody else being checked.
                    log.warn("Lab check of {} handle '{}' failed: {}", account.getPlatform(),
                        account.getHandle(), e.getMessage());
                }
            }
        }

        if (onlyUser == null) {
            List<Long> ids = toCheck.stream().map(LabAssignment::getLabId).toList();
            Instant at = Instant.now();
            transaction.executeWithoutResult(s -> labs.markChecked(ids, at));
        }
        return recorded;
    }

    /**
     * Records one accepted submission CPIntel has just watched being graded, for the problem it
     * was sent to. It came from the platform itself, through the student's own sign-in, so it is
     * taken as is rather than waiting for the next check.
     */
    public int recordWatched(Long userId, LabAssignment lab, LabProblem problem, String handle,
                             java.time.Instant at, String ref) {
        var pending = new SolveMatcher.Pending(problem.getLabProblemId(), problem.getProblemKey(),
            lab.getOpensAt(), lab.getDueAt(), Boolean.TRUE.equals(lab.getCountEarlierSolves()));
        List<SolveMatcher.Found> found = SolveMatcher.match(List.of(pending),
            List.of(new SolveMatcher.Accepted(problem.getProblemKey(), at, ref)));
        if (found.isEmpty()) return 0;
        SolveMatcher.Found f = found.get(0);
        Integer n = transaction.execute(s -> solves.record(f.problemId(), userId, f.at(), f.late(), handle, f.ref()));
        return n == null ? 0 : n;
    }

    private int checkOne(Long userId, PlatformAccount account, List<Target> targets) {
        List<SolveMatcher.Pending> pending = targets.stream()
            .map(t -> new SolveMatcher.Pending(t.problem().getLabProblemId(), t.problem().getProblemKey(),
                t.lab().getOpensAt(), t.lab().getDueAt(),
                Boolean.TRUE.equals(t.lab().getCountEarlierSolves())))
            .toList();

        List<SolveMatcher.Accepted> accepted = switch (account.getPlatform()) {
            case ProblemRef.CODEFORCES -> codeforcesAccepted(account.getHandle(),
                SolveMatcher.readBackTo(pending));
            case ProblemRef.LEETCODE -> leetCode.recentAccepted(account.getHandle()).stream()
                .map(s -> new SolveMatcher.Accepted(s.slug(), s.at(), s.id())).toList();
            case ProblemRef.CSES -> csesAccepted(account, pending);
            default -> List.of();
        };

        List<SolveMatcher.Found> found = SolveMatcher.match(pending, accepted);
        if (found.isEmpty()) return 0;
        Integer written = transaction.execute(s -> {
            int n = 0;
            for (SolveMatcher.Found f : found) {
                n += solves.record(f.problemId(), userId, f.at(), f.late(), account.getHandle(), f.ref());
            }
            return n;
        });
        return written == null ? 0 : written;
    }

    /**
     * Accepted CSES submissions to the pending tasks. One call says which tasks the student has
     * passed; only those have their submissions read, for the times.
     */
    private List<SolveMatcher.Accepted> csesAccepted(PlatformAccount account, List<SolveMatcher.Pending> pending) {
        if ("FAILED".equals(account.getSyncStatus()) || account.getAccessToken() == null) return List.of();
        String token = csesAccounts.token(account);
        try {
            java.util.Map<String, String> statuses = cses.statuses(token);
            List<SolveMatcher.Accepted> out = new ArrayList<>();
            for (String task : pending.stream().map(SolveMatcher.Pending::key).distinct().toList()) {
                if (!"pass".equals(statuses.get(task))) continue;
                for (var s : cses.submissions(token, task)) {
                    if ("pass".equals(s.outcome())) out.add(new SolveMatcher.Accepted(task, s.time(), String.valueOf(s.id())));
                }
            }
            return out;
        } catch (com.cpintel.integration.cses.CsesClient.TokenRejected e) {
            csesAccounts.markRejected(account);
            log.info("CSES sign-in for '{}' is no longer accepted; marked for reconnecting", account.getHandle());
            return List.of();
        }
    }

    /** Accepted Codeforces submissions back to {@code since}, newest first. */
    private List<SolveMatcher.Accepted> codeforcesAccepted(String handle, Instant since) {
        List<SolveMatcher.Accepted> out = new ArrayList<>();
        int from = 1;
        for (int page = 0; page < CF_MAX_PAGES; page++) {
            var resp = codeforces.getSubmissions(handle, from, CF_PAGE);
            List<CfModels.Submission> batch = resp == null || resp.getResult() == null
                ? List.of() : resp.getResult();
            boolean reachedSince = false;
            for (CfModels.Submission s : batch) {
                if (s.getCreationTimeSeconds() == null) continue;
                Instant at = Instant.ofEpochSecond(s.getCreationTimeSeconds());
                if (at.isBefore(since)) {
                    reachedSince = true;
                    continue;
                }
                if ("OK".equals(s.getVerdict()) && s.getProblem() != null
                        && s.getProblem().getContestId() != null && s.getProblem().getIndex() != null) {
                    out.add(new SolveMatcher.Accepted(
                        ProblemRef.codeforces(s.getProblem().getContestId(), s.getProblem().getIndex()).key(),
                        at, String.valueOf(s.getId())));
                }
            }
            if (reachedSince || batch.size() < CF_PAGE) break;
            from += CF_PAGE;
        }
        return out;
    }
}
