package com.cpintel.events;

import com.cpintel.classrooms.ClassroomService;
import com.cpintel.compete.CfContestScraper;
import com.cpintel.compete.CfManagerApi;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.integration.codeforces.CfContestRef;
import com.cpintel.integration.codeforces.CfStandingsResponse;
import com.cpintel.integration.codeforces.CodeforcesClient;
import com.cpintel.practice.CfSessionStore;
import com.cpintel.repository.jpa.UserRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Whether a Codeforces examination's candidates can sit it, checked before the day.
 *
 * <p>Three things have to be true for each candidate: they connected Codeforces in CPIntel
 * (known at once), and, through that connection, Codeforces lets them open the contest — for a
 * group contest that means they joined the group. The second is a page fetch per candidate, so
 * it runs in the background, a few at a time, and the admin's screen polls for the result.
 *
 * <p>For a group contest it also says whether the classroom's key reads the contest, and whether
 * the contest's own window covers the paper's: a contest that opens after the paper has started
 * leaves the room in front of a locked page.
 */
@Service
@Slf4j
public class CodeforcesReadinessService {

    private static final int PARALLEL = 3;

    private final EventService events;
    private final UserRepository users;
    private final CfSessionStore sessions;
    private final CfContestScraper scraper;
    private final CfManagerApi manager;
    private final ClassroomService classrooms;
    private final CodeforcesClient codeforces;

    private final ExecutorService pool = Executors.newFixedThreadPool(PARALLEL, r -> {
        Thread t = new Thread(r, "cf-readiness");
        t.setDaemon(true);
        return t;
    });

    /** Per event: when the last check started, each candidate's answer, and how many are left. */
    private record Run(Instant startedAt, Map<Long, Boolean> reachable, AtomicInteger pending) {}

    private final Map<Long, Run> runs = new ConcurrentHashMap<>();

    public CodeforcesReadinessService(EventService events, UserRepository users, CfSessionStore sessions,
                                      CfContestScraper scraper, CfManagerApi manager,
                                      ClassroomService classrooms, CodeforcesClient codeforces) {
        this.events = events;
        this.users = users;
        this.sessions = sessions;
        this.scraper = scraper;
        this.manager = manager;
        this.classrooms = classrooms;
        this.codeforces = codeforces;
    }

    @PreDestroy
    void stop() {
        pool.shutdownNow();
    }

    public EventsDto.CodeforcesReadiness state(Long eventId) {
        GroupContest event = requireCodeforces(eventId);
        CfContestRef ref = CfContestRef.parse(event.getExternalId());

        boolean keySet = classrooms.codeforcesKey(event.getClassroomId()) != null;
        String contestName = null;
        Instant contestStart = null;
        Long contestSeconds = null;
        String contestProblem = null;
        String contestPhase = null;
        try {
            CfStandingsResponse.Contest meta = null;
            if (ref.inGroup()) {
                if (keySet) {
                    CfStandingsResponse.Result result = manager.standings(ref);
                    meta = result == null ? null : result.getContest();
                } else {
                    contestProblem = "It is a private group contest and the classroom has no "
                        + "Codeforces API key, so verdicts cannot be read. Add a group manager's key "
                        + "on the classroom page.";
                }
            } else {
                meta = codeforces.getContestMeta(ref.id());
            }
            if (meta != null) {
                contestName = meta.getName();
                contestPhase = meta.getPhase();
                contestStart = meta.getStartTimeSeconds() == null ? null : Instant.ofEpochSecond(meta.getStartTimeSeconds());
                contestSeconds = meta.getDurationSeconds();
            }
        } catch (ApiException e) {
            contestProblem = "Codeforces refused the classroom's key for this contest: " + e.getMessage()
                + " The key must belong to a manager of the group.";
        } catch (Exception e) {
            contestProblem = "Codeforces could not be reached to read the contest.";
        }

        String windowProblem = windowProblem(event, contestStart, contestSeconds);
        if (contestProblem == null && contestPhase != null && contestStart == null) {
            contestProblem = "The Codeforces contest has no start time, so it can only be practised, not "
                + "sat or registered for. Give the mashup a start time that covers the paper.";
        } else if (contestProblem == null && "FINISHED".equals(contestPhase)) {
            contestProblem = "The Codeforces contest is already over, so it can only be practised. "
                + "Use a contest that starts in time for the paper.";
        }

        Run run = runs.get(eventId);
        List<EventsDto.CandidateReadiness> rows = new ArrayList<>();
        Set<Long> ids = events.participantIds(eventId);
        for (User u : users.findAllById(ids)) {
            CfSessionStore.StoredSession session = sessions.find(u.getUserId());
            Boolean reachable = run == null ? null : run.reachable().get(u.getUserId());
            rows.add(new EventsDto.CandidateReadiness(u.getUserId(), u.getUsername(), u.getFullName(),
                session != null, session == null ? null : session.handle(), reachable));
        }
        rows.sort(Comparator.comparing(EventsDto.CandidateReadiness::username,
            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));

        return new EventsDto.CodeforcesReadiness(ref.inGroup(), keySet, contestName, contestStart,
            contestSeconds, contestProblem, windowProblem,
            run != null && run.pending().get() > 0, run == null ? null : run.startedAt(), rows);
    }

    /** Starts checking, in the background, that each connected candidate can open the contest. */
    public EventsDto.CodeforcesReadiness check(Long eventId) {
        GroupContest event = requireCodeforces(eventId);
        CfContestRef ref = CfContestRef.parse(event.getExternalId());
        Run current = runs.get(eventId);
        if (current != null && current.pending().get() > 0) return state(eventId);

        // Only a session CPIntel holds cookies for can be opened from here. One connected through
        // the desktop app or the extension keeps only its handle (its browser does the fetching),
        // so it stays unchecked rather than being reported as unable to open the contest.
        List<Long> connected = events.participantIds(eventId).stream()
            .filter(id -> {
                CfSessionStore.StoredSession s = sessions.find(id);
                return s != null && s.cookieHeader() != null && !s.cookieHeader().isBlank();
            }).toList();
        Run run = new Run(Instant.now(), new ConcurrentHashMap<>(), new AtomicInteger(connected.size()));
        runs.put(eventId, run);
        for (Long userId : connected) {
            pool.submit(() -> {
                try {
                    CfSessionStore.StoredSession session = sessions.find(userId);
                    run.reachable().put(userId, session != null && scraper.canOpen(session.cookieHeader(), ref));
                } catch (Exception e) {
                    log.debug("Readiness check for user {} on {} failed: {}", userId, ref, e.getMessage());
                    run.reachable().put(userId, false);
                } finally {
                    run.pending().decrementAndGet();
                }
            });
        }
        return state(eventId);
    }

    static String windowProblem(GroupContest event, Instant contestStart, Long contestSeconds) {
        if (contestStart == null || contestSeconds == null || event.getStartsAt() == null
                || event.getEndsAt() == null) {
            return null;
        }
        Instant contestEnd = contestStart.plusSeconds(contestSeconds);
        if (contestStart.isAfter(event.getStartsAt().plus(Duration.ofMinutes(1)))) {
            return "The Codeforces contest opens after the paper starts, so candidates will wait "
                + "in front of a locked contest.";
        }
        if (contestEnd.isBefore(event.getEndsAt().minus(Duration.ofMinutes(1)))) {
            return "The Codeforces contest closes before the paper ends, so the last part of the "
                + "paper cannot be submitted.";
        }
        return null;
    }

    private GroupContest requireCodeforces(Long eventId) {
        GroupContest event = events.require(eventId);
        if (!GroupContest.Platform.CODEFORCES.name().equals(event.getPlatform())) {
            throw ApiException.badRequest("Only a Codeforces exam or contest has this check.");
        }
        return event;
    }
}
