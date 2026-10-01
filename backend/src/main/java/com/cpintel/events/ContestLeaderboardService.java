package com.cpintel.events;

import com.cpintel.compete.CompeteDto;
import com.cpintel.compete.CompeteProvider;
import com.cpintel.compete.CompeteService;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.User;
import com.cpintel.entity.mongo.CodeSubmission;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The compete arena's leaderboard: an examination's board, for a contest.
 *
 * <p>The arena used to show the judge's own scoreboard. That board is only as visible as the
 * judge makes it — DOMjudge leaves a whole team category off the public view, and a team account
 * is refused the other one — so a room of contestants could open it and find nobody on it. This
 * one is ranked by {@link ExamLeaderboardService} from CPIntel's submission archive, which an
 * ordinary account can always be shown.
 *
 * <p>A contest with a CPIntel event behind it is that event's board, under the settings its
 * admin chose. One opened straight from the judge has no event, so what an event would have
 * supplied comes from the judge's contest instead: its window, its problems, each worth one,
 * no penalty, and whoever submitted through CPIntel as the roster.
 */
@Service
@RequiredArgsConstructor
public class ContestLeaderboardService {

    /** Nobody sets a schedule for a contest with no event, so it moves at the fastest one. */
    private static final int REFRESH_MINUTES = 1;

    private final ExamLeaderboardService boards;
    private final ExamSessionGuard examGuard;
    private final EventWindow eventWindow;
    private final CompeteService compete;
    private final UserRepository userRepository;

    /**
     * Boards for contests with no event, which have no row to be stored on. Per instance, so
     * two instances can be up to a refresh apart — and both are rebuilt from the same archive.
     */
    private final Map<String, EventsDto.LeaderboardStandings> stored = new ConcurrentHashMap<>();

    public EventsDto.Leaderboard forContest(Long userId, String platform, String contestId) {
        examGuard.requireContestAccess(userId, platform, contestId);
        CompeteProvider provider = compete.provider(platform);
        // A Codeforces round is sat by the world; the few who sent their code through CPIntel
        // are not its standings.
        if (!CompeteDto.Platform.DOMJUDGE.name().equals(provider.platform())) {
            throw ApiException.badRequest(
                provider.platform() + " contests have no leaderboard here.");
        }

        Optional<GroupContest> event = eventWindow.governing(userId, provider.platform(), contestId);
        if (event.isPresent()) return boards.forParticipant(event.get());

        CompeteDto.ContestInfo info = provider.contestInfo(userId, contestId);
        GroupContest round = roundOf(info);
        Instant now = Instant.now();
        String state = boards.candidateState(round, now);
        return boards.answer(round, state,
            "NOT_STARTED".equals(state) ? null : current(round, info, now), now);
    }

    /**
     * This person's place on that board, for the arena's header.
     *
     * <p>Taken from the board rather than from the judge so the two agree: the judge's
     * scoreboard has no row for a team it keeps off the public view, and the header then said
     * "no rank, nothing solved" beside a board that ranked them. It moves when the board does.
     * Where the board is not shown — switched off, or not released — neither is a place on it.
     */
    public CompeteDto.RankInfo rank(Long userId, String platform, String contestId) {
        if (!CompeteDto.Platform.DOMJUDGE.name().equals(compete.provider(platform).platform())) {
            return compete.rank(userId, platform, contestId);
        }

        EventsDto.LeaderboardStandings standings =
            forContest(userId, platform, contestId).standings();
        if (standings == null) {
            return new CompeteDto.RankInfo(null, null, null, 0, false, false, Instant.now());
        }
        for (EventsDto.LeaderboardRow row : standings.rows()) {
            if (!userId.equals(row.userId())) continue;
            return new CompeteDto.RankInfo(row.rank(),
                // Marks only where the admin set some; otherwise they are the solve count again.
                standings.marked() ? row.score() : null,
                (int) (row.totalSeconds() / 60), row.solved(), false, true,
                standings.generatedAt());
        }
        // Not on the board yet — normal before a first submission to a contest with no roster.
        return new CompeteDto.RankInfo(null, null, null, 0, false, true, standings.generatedAt());
    }

    /** The judge's contest, in the shape the ranking takes an event in. Never saved. */
    private static GroupContest roundOf(CompeteDto.ContestInfo info) {
        Instant start = info.startsAt();
        return GroupContest.builder()
            .kind(GroupContest.Kind.CONTEST.name())
            .platform(info.platform())
            .externalId(info.id())
            .name(info.name())
            .startsAt(start)
            .endsAt(start == null || info.durationSeconds() <= 0
                ? null : start.plusSeconds(info.durationSeconds()))
            .lifecycle(GroupContest.Lifecycle.SCHEDULED.name())
            .leaderboardRefreshMinutes(REFRESH_MINUTES)
            .build();
    }

    private EventsDto.LeaderboardStandings current(GroupContest round,
                                                   CompeteDto.ContestInfo info, Instant now) {
        String key = round.getPlatform() + "|" + round.getExternalId();
        EventsDto.LeaderboardStandings held = stored.get(key);
        if (held != null && !boards.due(round, held.generatedAt(), now)) return held;
        // One recompute however many people the refresh found reading it.
        return stored.compute(key, (k, latest) ->
            latest != null && !boards.due(round, latest.generatedAt(), now)
                ? latest : compute(round, info, now));
    }

    private EventsDto.LeaderboardStandings compute(GroupContest round,
                                                   CompeteDto.ContestInfo info, Instant now) {
        List<CodeSubmission> rows =
            boards.refreshPending(round, boards.attempts(round, null), null);

        List<String> labels = info.problems().stream()
            .map(CompeteDto.ContestProblem::index)
            .filter(label -> label != null && !label.isBlank())
            .map(label -> label.toUpperCase(Locale.ROOT))
            .distinct()
            .toList();

        Set<Long> submitters = new HashSet<>();
        for (CodeSubmission row : rows) submitters.add(row.getUserId());
        Map<Long, User> users = new HashMap<>();
        for (User user : userRepository.findAllById(submitters)) users.put(user.getUserId(), user);

        return boards.rank(round, labels, Map.of(), users, rows, now);
    }
}
