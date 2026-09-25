package com.cpintel.events;

import com.cpintel.compete.CompeteService;
import com.cpintel.entity.ContestProblem;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.User;
import com.cpintel.entity.mongo.CodeSubmission;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.ContestProblemRepository;
import com.cpintel.repository.jpa.GroupContestRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.repository.mongo.CodeSubmissionRepository;
import com.cpintel.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * An examination's own leaderboard.
 *
 * <p>Ranked from CPIntel's submission archive rather than the judge's scoreboard. The archive
 * row is written the moment CPIntel sends the code, before the judge has seen it, so a solve is
 * timed from when the candidate submitted — not from when a busy judge got round to answering.
 * Two candidates who submit a second apart are ordered by that second even if the later one's
 * verdict came back first.
 *
 * <p>Order: more problems solved first; among equal counts, less total time. Total time is, for
 * each solved problem, the time from the start of the paper to the accepted submission, plus the
 * configured penalty for each wrong attempt on it before that. Compilation errors and
 * submissions the judge never accepted are not wrong attempts.
 *
 * <p>What candidates see is a snapshot, recomputed at most every
 * {@code leaderboardRefreshMinutes} while the paper runs and stored on the event so every
 * instance serves the same board. Once the paper is over the board keeps settling for a few
 * minutes, to pick up verdicts that were still being judged at the bell, and is then final.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExamLeaderboardService {

    /** How long after the end late verdicts are still folded in before the board is final. */
    static final Duration SETTLE = Duration.ofMinutes(10);

    /** While settling, recompute no more often than this, however many people are reading it. */
    private static final Duration SETTLE_GAP = Duration.ofMinutes(1);

    /** Candidates whose pending verdicts are fetched from the judge per recompute, at most. */
    private static final int MAX_VERDICT_REFRESHES = 50;

    private static final Set<String> PENDING = Set.of("SUBMITTING", "TESTING");

    /** Not the candidate's mistake in the sense a penalty is for, or never reached the judge. */
    private static final Set<String> NOT_COUNTED = Set.of(
        "NOT_SUBMITTED", "COMPILATION_ERROR", "SKIPPED", "REJECTED");

    private final EventService events;
    private final GroupContestRepository eventRepository;
    private final ContestProblemRepository problemRepository;
    private final CodeSubmissionRepository submissions;
    private final UserRepository userRepository;
    private final CompeteService compete;
    private final AuditService auditService;
    private final ObjectMapper json;

    // --------------------------------------------------------------- readers

    /** The board as an admin sees it: always computed once started, whatever candidates see. */
    public EventsDto.Leaderboard forAdmin(Long eventId, boolean refresh) {
        GroupContest event = requireExam(eventId);
        Instant now = Instant.now();
        EventsDto.LeaderboardStandings standings = event.hasStarted(now)
            ? current(event, now, refresh) : null;
        return answer(event, candidateState(event, now), standings, now);
    }

    /** The board as a candidate may see it, if they may see it at all. */
    public EventsDto.Leaderboard forCandidate(Long userId, Long eventId) {
        GroupContest event = events.requireAssigned(userId, eventId);
        if (!event.isExam()) throw ApiException.notFound("No such examination");
        Instant now = Instant.now();
        String state = candidateState(event, now);
        EventsDto.LeaderboardStandings standings =
            "LIVE".equals(state) || "FINAL".equals(state) ? current(event, now, false) : null;
        return answer(event, state, standings, now);
    }

    // ---------------------------------------------------------------- writes

    @Transactional
    public EventsDto.Leaderboard updateSettings(Long adminId, Long eventId,
                                                EventsDto.LeaderboardSettings req,
                                                HttpServletRequest httpReq) {
        GroupContest event = requireExam(eventId);
        boolean penaltyChanged = !req.penaltyMinutes().equals(event.getWrongPenaltyMinutes());

        event.setLeaderboardEnabled(req.enabled());
        event.setLeaderboardRefreshMinutes(req.refreshMinutes());
        event.setWrongPenaltyMinutes(req.penaltyMinutes());
        event.setLeaderboardFinalPublic(req.finalPublic());
        // A new penalty re-ranks everybody; the stored board would otherwise carry the old one
        // until its next refresh, and a final board would carry it for ever.
        if (penaltyChanged) {
            event.setLeaderboardSnapshot(null);
            event.setLeaderboardGeneratedAt(null);
        }
        eventRepository.save(event);

        auditService.record(adminId, AuditService.EVENT_UPDATED, event.getKind(),
            String.valueOf(eventId), httpReq);
        Instant now = Instant.now();
        return answer(event, candidateState(event, now), null, now);
    }

    // --------------------------------------------------------------- the board

    private String candidateState(GroupContest event, Instant now) {
        if (!Boolean.TRUE.equals(event.getLeaderboardEnabled())) return "DISABLED";
        if (!event.hasStarted(now)) return "NOT_STARTED";
        if (event.getEndsAt() == null || now.isBefore(event.getEndsAt())) return "LIVE";
        return Boolean.TRUE.equals(event.getLeaderboardFinalPublic()) ? "FINAL" : "UNPUBLISHED";
    }

    private EventsDto.Leaderboard answer(GroupContest event, String state,
                                         EventsDto.LeaderboardStandings standings, Instant now) {
        Instant next = null;
        if (standings != null && event.getEndsAt() != null && now.isBefore(event.getEndsAt())) {
            next = standings.generatedAt().plus(refreshInterval(event));
            if (next.isAfter(event.getEndsAt())) next = event.getEndsAt();
        }
        return new EventsDto.Leaderboard(event.getContestId(), event.getName(), state,
            settingsOf(event), standings, next);
    }

    private static EventsDto.LeaderboardSettings settingsOf(GroupContest event) {
        return new EventsDto.LeaderboardSettings(
            !Boolean.FALSE.equals(event.getLeaderboardEnabled()),
            event.getLeaderboardRefreshMinutes() == null ? 15 : event.getLeaderboardRefreshMinutes(),
            event.getWrongPenaltyMinutes() == null ? 0 : event.getWrongPenaltyMinutes(),
            !Boolean.FALSE.equals(event.getLeaderboardFinalPublic()));
    }

    private static Duration refreshInterval(GroupContest event) {
        Integer minutes = event.getLeaderboardRefreshMinutes();
        return Duration.ofMinutes(minutes == null || minutes < 1 ? 15 : minutes);
    }

    /** The stored board, recomputed first if it is due. */
    private EventsDto.LeaderboardStandings current(GroupContest event, Instant now,
                                                   boolean force) {
        EventsDto.LeaderboardStandings stored = parse(event.getLeaderboardSnapshot());
        Instant at = stored == null ? null : stored.generatedAt();
        if (!force && at != null && !due(event, at, now)) return stored;

        EventsDto.LeaderboardStandings fresh = compute(event, now);
        try {
            eventRepository.storeLeaderboard(event.getContestId(),
                json.writeValueAsString(fresh), fresh.generatedAt());
        } catch (Exception e) {
            // Serving a fresh board that could not be stored is still better than none.
            log.warn("Could not store the leaderboard for exam {}: {}",
                event.getContestId(), e.getMessage());
        }
        return fresh;
    }

    private boolean due(GroupContest event, Instant generatedAt, Instant now) {
        if (event.getStartsAt() != null && generatedAt.isBefore(event.getStartsAt())) return true;
        Instant end = event.getEndsAt();
        if (end == null || now.isBefore(end)) {
            return !generatedAt.plus(refreshInterval(event)).isAfter(now);
        }
        // Over. The first read after the bell always recomputes; then keep settling for a few
        // minutes while late verdicts land, and after that the board is final.
        if (generatedAt.isBefore(end)) return true;
        return generatedAt.isBefore(end.plus(SETTLE))
            && !generatedAt.plus(SETTLE_GAP).isAfter(now);
    }

    private EventsDto.LeaderboardStandings parse(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) return null;
        try {
            return json.readValue(snapshot, EventsDto.LeaderboardStandings.class);
        } catch (Exception e) {
            log.debug("Discarding an unreadable leaderboard snapshot: {}", e.getMessage());
            return null;
        }
    }

    EventsDto.LeaderboardStandings compute(GroupContest event, Instant now) {
        Set<Long> participants = events.participantIds(event.getContestId());

        List<CodeSubmission> rows = attempts(event, participants);
        rows = refreshPending(event, rows, participants);

        List<String> labels = labels(event, rows);
        Map<Long, User> users = new HashMap<>();
        for (User user : userRepository.findAllById(participants)) users.put(user.getUserId(), user);

        return rank(event, labels, users, rows, now);
    }

    /** This paper's attempts: its judge contest, its candidates, inside its window. */
    private List<CodeSubmission> attempts(GroupContest event, Set<Long> participants) {
        return submissions.findByPlatformAndContestId(event.getPlatform(), event.getExternalId())
            .stream()
            .filter(row -> row.getUserId() != null && participants.contains(row.getUserId()))
            .filter(row -> LiveExamGuard.madeDuring(event, row.getSubmittedAt()))
            .toList();
    }

    /**
     * Asks the judge about anything still marked as judging.
     *
     * <p>The archive learns a verdict when the candidate's own screen polls for it. Somebody who
     * submitted and closed the window, or whose paper ended mid-judgement, would otherwise stay
     * "judging" on the board for ever. Reading their submissions list as them moves the archive
     * on, through the same path their own screen uses.
     */
    private List<CodeSubmission> refreshPending(GroupContest event, List<CodeSubmission> rows,
                                                Set<Long> participants) {
        Set<Long> waiting = new LinkedHashSet<>();
        for (CodeSubmission row : rows) {
            if (row.getExternalId() != null && isPending(row.getVerdict())) {
                waiting.add(row.getUserId());
            }
        }
        if (waiting.isEmpty()) return rows;

        int asked = 0;
        for (Long userId : waiting) {
            if (asked++ >= MAX_VERDICT_REFRESHES) break;
            try {
                compete.provider(event.getPlatform()).submissions(userId, event.getExternalId());
            } catch (Exception e) {
                log.debug("Could not refresh verdicts for user {} on exam {}: {}",
                    userId, event.getContestId(), e.getMessage());
            }
        }
        return attempts(event, participants);
    }

    /** The paper's problems in order, or whatever was submitted to if none are listed. */
    private List<String> labels(GroupContest event, List<CodeSubmission> rows) {
        List<String> listed = problemRepository
            .findByContestContestIdOrderByOrderingAscLabelAsc(event.getContestId()).stream()
            .map(ContestProblem::getLabel)
            .filter(label -> label != null && !label.isBlank())
            .map(label -> label.toUpperCase(Locale.ROOT))
            .distinct()
            .toList();
        if (!listed.isEmpty()) return listed;

        Set<String> seen = new TreeSet<>();
        for (CodeSubmission row : rows) {
            if (row.getProblemIndex() != null) seen.add(row.getProblemIndex().toUpperCase(Locale.ROOT));
        }
        return List.copyOf(seen);
    }

    /** The ranking itself. Package-visible for the tests. */
    EventsDto.LeaderboardStandings rank(GroupContest event, List<String> labels,
                                        Map<Long, User> users, List<CodeSubmission> rows,
                                        Instant now) {
        long penaltySeconds = 60L * (event.getWrongPenaltyMinutes() == null
            ? 0 : event.getWrongPenaltyMinutes());
        Instant start = event.getStartsAt();

        // user → label → attempts, oldest first by the time CPIntel sent them.
        Map<Long, Map<String, List<CodeSubmission>>> byUser = new HashMap<>();
        int pendingTotal = 0;
        for (CodeSubmission row : rows) {
            String label = row.getProblemIndex() == null
                ? null : row.getProblemIndex().toUpperCase(Locale.ROOT);
            if (label == null || !labels.contains(label)) continue;
            byUser.computeIfAbsent(row.getUserId(), k -> new HashMap<>())
                .computeIfAbsent(label, k -> new ArrayList<>()).add(row);
            if (isPending(row.getVerdict())) pendingTotal++;
        }

        List<Scored> scored = new ArrayList<>();
        for (User user : users.values()) {
            Map<String, List<CodeSubmission>> mine = byUser.getOrDefault(user.getUserId(), Map.of());
            List<EventsDto.LeaderboardCell> cells = new ArrayList<>(labels.size());
            int solved = 0;
            long total = 0;
            long lastSolve = 0;

            for (String label : labels) {
                List<CodeSubmission> attempts = new ArrayList<>(mine.getOrDefault(label, List.of()));
                attempts.sort(Comparator.comparing(CodeSubmission::getSubmittedAt));

                int wrong = 0;
                boolean pending = false;
                Long solvedAt = null;
                for (CodeSubmission attempt : attempts) {
                    String verdict = attempt.getVerdict();
                    if ("OK".equals(verdict)) {
                        solvedAt = Math.max(0,
                            Duration.between(start, attempt.getSubmittedAt()).toSeconds());
                        break;
                    }
                    if (isPending(verdict)) pending = true;
                    else if (!NOT_COUNTED.contains(verdict)) wrong++;
                }

                if (solvedAt != null) {
                    solved++;
                    total += solvedAt + wrong * penaltySeconds;
                    lastSolve = Math.max(lastSolve, solvedAt);
                }
                cells.add(new EventsDto.LeaderboardCell(
                    label, solvedAt != null, wrong, solvedAt, pending, false));
            }
            scored.add(new Scored(user, solved, total, lastSolve, cells));
        }

        scored.sort(Comparator
            .comparingInt(Scored::solved).reversed()
            .thenComparingLong(Scored::total)
            .thenComparingLong(Scored::lastSolve)
            .thenComparing(s -> s.user().getUsername(), String.CASE_INSENSITIVE_ORDER));

        // First to solve each problem, by submission time.
        Map<String, Long> firstSolve = new HashMap<>();
        for (Scored s : scored) {
            for (EventsDto.LeaderboardCell cell : s.cells()) {
                if (cell.solved()) firstSolve.merge(cell.label(), cell.solvedAtSeconds(), Math::min);
            }
        }

        List<EventsDto.LeaderboardRow> out = new ArrayList<>(scored.size());
        int rank = 0;
        Scored previous = null;
        for (int i = 0; i < scored.size(); i++) {
            Scored s = scored.get(i);
            // Equal solves and equal time share a rank; the next distinct one skips past them.
            if (previous == null || previous.solved() != s.solved() || previous.total() != s.total()) {
                rank = i + 1;
            }
            previous = s;
            List<EventsDto.LeaderboardCell> cells = s.cells().stream()
                .map(c -> c.solved() && c.solvedAtSeconds().equals(firstSolve.get(c.label()))
                    ? new EventsDto.LeaderboardCell(c.label(), true, c.wrongAttempts(),
                        c.solvedAtSeconds(), c.pending(), true)
                    : c)
                .toList();
            out.add(new EventsDto.LeaderboardRow(rank, s.user().getUserId(),
                s.user().getUsername(), s.user().getFullName(), s.solved(), s.total(), cells));
        }

        return new EventsDto.LeaderboardStandings(labels, out,
            (int) (penaltySeconds / 60), pendingTotal, now);
    }

    private static boolean isPending(String verdict) {
        return verdict == null || PENDING.contains(verdict);
    }

    private GroupContest requireExam(Long eventId) {
        GroupContest event = events.require(eventId);
        if (!event.isExam()) {
            throw ApiException.badRequest("Only an examination has its own leaderboard.");
        }
        return event;
    }

    private record Scored(User user, int solved, long total, long lastSolve,
                          List<EventsDto.LeaderboardCell> cells) {}
}
