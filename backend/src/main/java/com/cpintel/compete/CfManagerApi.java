package com.cpintel.compete;

import com.cpintel.classrooms.ClassroomService;
import com.cpintel.entity.GroupContest;
import com.cpintel.integration.codeforces.CfContestRef;
import com.cpintel.integration.codeforces.CfModels;
import com.cpintel.integration.codeforces.CfStandingsResponse;
import com.cpintel.integration.codeforces.CfSubmissionsResponse;
import com.cpintel.integration.codeforces.CodeforcesClient;
import com.cpintel.repository.jpa.GroupContestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads a private group contest through its classroom's Codeforces API key.
 *
 * <p>The anonymous API refuses a group contest, so its verdicts and problems come from
 * {@code contest.status} and {@code contest.standings} signed as the group's manager. One status
 * call returns the whole room's submissions, so it is cached briefly and shared: a room of
 * candidates polling their verdicts costs one call every few seconds, not one each.
 *
 * <p>Public contests keep using the anonymous API; this returns null for them, and for a group
 * contest whose classroom has no key.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CfManagerApi {

    private static final Duration STATUS_TTL = Duration.ofSeconds(10);
    private static final Duration STANDINGS_TTL = Duration.ofMinutes(2);

    private final CodeforcesClient client;
    private final GroupContestRepository events;
    private final ClassroomService classrooms;

    private record Cached<T>(T value, Instant at) {}

    private final Map<String, Cached<List<CfModels.Submission>>> statusCache = new ConcurrentHashMap<>();
    private final Map<String, Cached<CfStandingsResponse.Result>> standingsCache = new ConcurrentHashMap<>();

    /** The key of the first classroom with an event on this group contest that has one. */
    public CodeforcesClient.ApiKey keyFor(CfContestRef ref) {
        if (!ref.inGroup()) return null;
        for (GroupContest event : events.findByPlatformAndExternalId(
                GroupContest.Platform.CODEFORCES.name(), ref.encoded())) {
            if (event.getClassroomId() == null) continue;
            CodeforcesClient.ApiKey key = classrooms.codeforcesKey(event.getClassroomId());
            if (key != null) return key;
        }
        return null;
    }

    /** Every submission in the contest, newest first; null when there is no key to ask with. */
    public List<CfModels.Submission> status(CfContestRef ref) {
        CodeforcesClient.ApiKey key = keyFor(ref);
        if (key == null) return null;
        Cached<List<CfModels.Submission>> hit = statusCache.get(ref.encoded());
        if (hit != null && Instant.now().isBefore(hit.at().plus(STATUS_TTL))) return hit.value();
        CfSubmissionsResponse resp = client.getContestStatusAsManager(ref.id(), key);
        List<CfModels.Submission> rows = resp == null || resp.getResult() == null ? List.of() : resp.getResult();
        statusCache.put(ref.encoded(), new Cached<>(rows, Instant.now()));
        return rows;
    }

    /** One handle's submissions in the contest; null when there is no key to ask with. */
    public List<CfModels.Submission> statusOf(CfContestRef ref, String handle) {
        List<CfModels.Submission> all = status(ref);
        if (all == null) return null;
        return all.stream().filter(s -> byHandle(s, handle)).toList();
    }

    /** The contest's details and problems; null when there is no key to ask with. */
    public CfStandingsResponse.Result standings(CfContestRef ref) {
        CodeforcesClient.ApiKey key = keyFor(ref);
        if (key == null) return null;
        Cached<CfStandingsResponse.Result> hit = standingsCache.get(ref.encoded());
        if (hit != null && Instant.now().isBefore(hit.at().plus(STANDINGS_TTL))) return hit.value();
        CfStandingsResponse resp = client.getStandingsAsManager(ref.id(), key);
        CfStandingsResponse.Result result = resp == null ? null : resp.getResult();
        if (result != null) standingsCache.put(ref.encoded(), new Cached<>(result, Instant.now()));
        return result;
    }

    static boolean byHandle(CfModels.Submission s, String handle) {
        if (handle == null || s.getAuthor() == null || s.getAuthor().getMembers() == null) return false;
        return s.getAuthor().getMembers().stream()
            .anyMatch(m -> m.getHandle() != null && m.getHandle().equalsIgnoreCase(handle));
    }
}
