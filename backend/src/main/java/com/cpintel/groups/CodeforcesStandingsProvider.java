package com.cpintel.groups;

import com.cpintel.entity.GroupContest;
import com.cpintel.integration.codeforces.CfModels;
import com.cpintel.integration.codeforces.CodeforcesClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Group standings on Codeforces, built from each member's own submission list.
 *
 * Codeforces will not answer the question we actually want to ask. `contest.standings` refuses
 * every filtering parameter for non-gym contests, and the unfiltered form returns the entire
 * ranklist — megabytes of rows belonging to people who are not in the group. What it will
 * answer is `contest.status?handle=`, one competitor at a time, and that is what this uses.
 *
 * The consequence worth being clear about: the numbers here are computed from submissions, not
 * copied from Codeforces' own scoreboard. For an ICPC-mode round they will match. For a regular
 * rated round, which scores by decaying problem points rather than by penalty minutes, the
 * group's internal order can differ from the official board's. That is a deliberate trade —
 * the group ranking is a ranking of the group, and it is labelled as computed everywhere it is
 * shown, rather than being passed off as Codeforces' own.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CodeforcesStandingsProvider implements StandingsProvider {

    /** Minutes added per rejected attempt on a problem that is eventually solved. */
    private static final int PENALTY_PER_WRONG = 10;

    /** Verdicts that do not count as an attempt — the submission never really ran. */
    private static final List<String> NOT_AN_ATTEMPT = List.of("COMPILATION_ERROR", "SKIPPED", "TESTING");

    private final CodeforcesClient codeforces;
    private final ObjectMapper objectMapper;

    @Override
    public String platform() {
        return GroupContest.Platform.CODEFORCES.name();
    }

    @Override
    public List<Result> fetch(GroupContest contest, List<Competitor> competitors) {
        int contestId;
        try {
            contestId = Integer.parseInt(contest.getExternalId().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "'" + contest.getExternalId() + "' is not a Codeforces contest id");
        }

        List<Result> results = new ArrayList<>(competitors.size());
        for (Competitor competitor : competitors) {
            results.add(fetchOne(contestId, contest.getStartsAt(), competitor));
        }
        return results;
    }

    /**
     * One competitor, and never a thrown exception.
     *
     * A handle Codeforces has never heard of, a rate limit, a timeout — all of them mean this
     * person could not be read, not that the refresh failed. Letting one bad handle abort the
     * whole group would make the board disappear for everyone else.
     */
    private Result fetchOne(int contestId, Instant startsAt, Competitor competitor) {
        if (!StringUtils.hasText(competitor.handle())) {
            return new Result(competitor.userId(), null, false, 0, 0, null, null);
        }

        List<CfModels.Submission> submissions;
        try {
            var response = codeforces.getContestStatus(contestId, competitor.handle());
            submissions = response == null || response.getResult() == null
                ? List.of() : response.getResult();
        } catch (Exception e) {
            log.debug("CF standings: could not read {} in contest {}: {}",
                competitor.handle(), contestId, e.getMessage());
            return new Result(competitor.userId(), competitor.handle(), false, 0, 0, null, null);
        }

        if (submissions.isEmpty()) {
            // No submissions is a real, meaningful answer: they are on the board with nothing
            // solved. Distinct from the failures above, which report found=false.
            return new Result(competitor.userId(), competitor.handle(), true, 0, 0, 0.0, "{}");
        }

        return score(competitor, submissions, startsAt);
    }

    /**
     * Turns a submission list into solved count and penalty.
     *
     * Codeforces returns newest first, so the list is walked in reverse to see the contest in
     * the order it was actually sat — which is the only way "attempts before the accepted one"
     * means anything.
     */
    private Result score(Competitor competitor, List<CfModels.Submission> submissions,
                         Instant startsAt) {
        Map<String, ProblemState> byProblem = new LinkedHashMap<>();

        for (int i = submissions.size() - 1; i >= 0; i--) {
            CfModels.Submission submission = submissions.get(i);
            if (submission.getProblem() == null || submission.getProblem().getIndex() == null) continue;

            String index = submission.getProblem().getIndex();
            ProblemState state = byProblem.computeIfAbsent(index, k -> new ProblemState());
            if (state.solvedAtMinute != null) continue;   // already done; later attempts are free

            String verdict = submission.getVerdict();
            if ("OK".equals(verdict)) {
                state.solvedAtMinute = minutesInto(startsAt, submission.getCreationTimeSeconds());
            } else if (verdict != null && !NOT_AN_ATTEMPT.contains(verdict)) {
                state.wrongAttempts++;
            }
        }

        int solved = 0;
        int penalty = 0;
        Map<String, Object> detail = new LinkedHashMap<>();

        for (var entry : byProblem.entrySet()) {
            ProblemState state = entry.getValue();
            boolean done = state.solvedAtMinute != null;
            if (done) {
                solved++;
                penalty += state.solvedAtMinute + state.wrongAttempts * PENALTY_PER_WRONG;
            }
            detail.put(entry.getKey(), Map.of(
                "solved", done,
                "attempts", state.wrongAttempts + (done ? 1 : 0),
                "minute", done ? state.solvedAtMinute : -1
            ));
        }

        return new Result(competitor.userId(), competitor.handle(), true, solved, penalty,
            (double) solved, writeJson(detail));
    }

    /**
     * Minutes from the contest start to a submission.
     *
     * Clamped at zero rather than trusted: an upsolve after the round, or a start time the
     * admin typed in wrong, would otherwise produce a negative penalty and float that member
     * to the top of the board.
     */
    private int minutesInto(Instant startsAt, Long creationTimeSeconds) {
        if (startsAt == null || creationTimeSeconds == null) return 0;
        long minutes = (creationTimeSeconds - startsAt.getEpochSecond()) / 60;
        return (int) Math.max(0, minutes);
    }

    private String writeJson(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            // The detail column is a convenience for the board's per-problem cells. Losing it
            // must not cost the solved count and penalty that sit beside it.
            log.debug("Could not serialise standings detail: {}", e.getMessage());
            return null;
        }
    }

    private static final class ProblemState {
        Integer solvedAtMinute;
        int wrongAttempts;
    }
}
