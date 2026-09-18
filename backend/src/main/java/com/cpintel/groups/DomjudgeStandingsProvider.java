package com.cpintel.groups;

import com.cpintel.entity.GroupContest;
import com.cpintel.integration.domjudge.DjModels;
import com.cpintel.integration.domjudge.DomjudgeClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Group standings on DOMjudge, filtered out of the full scoreboard.
 *
 * The opposite shape to Codeforces: one request returns every row, so the work here is
 * matching group members to teams rather than fetching them one at a time. Solved counts and
 * penalty minutes are DOMjudge's own — this reads the official board rather than recomputing
 * it, which is why these numbers agree with the judge's and the Codeforces ones carry a caveat.
 *
 * Matching is by team name, case- and space-insensitively, against both the team's name and
 * its display name. DOMjudge installations differ in which of the two is the human-facing one,
 * and an admin typing what they see on the scoreboard should not have to know which.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DomjudgeStandingsProvider implements StandingsProvider {

    private final DomjudgeClient domjudge;
    private final ObjectMapper objectMapper;

    @Override
    public String platform() {
        return GroupContest.Platform.DOMJUDGE.name();
    }

    @Override
    public List<Result> fetch(GroupContest contest, List<Competitor> competitors) {
        String contestId = contest.getExternalId().trim();

        // Both calls are allowed to throw: unlike a single unreadable handle on Codeforces, an
        // unreachable DOMjudge means nothing at all can be read, and reporting that as every
        // member scoring zero would be a lie the admin might act on.
        List<DjModels.Team> teams = domjudge.getTeams(contestId);
        DjModels.Scoreboard scoreboard = domjudge.getScoreboard(contestId);

        Map<String, DjModels.Row> rowByTeamId = new HashMap<>();
        if (scoreboard != null && scoreboard.getRows() != null) {
            for (DjModels.Row row : scoreboard.getRows()) {
                if (row.getTeam_id() != null) rowByTeamId.put(row.getTeam_id(), row);
            }
        }

        Map<String, String> teamIdByName = new HashMap<>();
        if (teams != null) {
            for (DjModels.Team team : teams) {
                if (team.getId() == null) continue;
                // Last write wins on a collision, which only happens when an installation has
                // two teams with the same visible name — ambiguous by construction.
                if (StringUtils.hasText(team.getName())) {
                    teamIdByName.put(normalise(team.getName()), team.getId());
                }
                if (StringUtils.hasText(team.getDisplay_name())) {
                    teamIdByName.put(normalise(team.getDisplay_name()), team.getId());
                }
            }
        }

        List<Result> results = new ArrayList<>(competitors.size());
        for (Competitor competitor : competitors) {
            results.add(match(competitor, teamIdByName, rowByTeamId));
        }
        return results;
    }

    private Result match(Competitor competitor, Map<String, String> teamIdByName,
                         Map<String, DjModels.Row> rowByTeamId) {
        if (!StringUtils.hasText(competitor.handle())) {
            return new Result(competitor.userId(), null, false, 0, 0, null, null);
        }

        String teamId = teamIdByName.get(normalise(competitor.handle()));
        DjModels.Row row = teamId == null ? null : rowByTeamId.get(teamId);
        if (row == null) {
            // Either the team name does not exist on this contest, or it exists but has no
            // scoreboard row yet. Both read as "not found", which is the honest answer.
            return new Result(competitor.userId(), competitor.handle(), false, 0, 0, null, null);
        }

        int solved = row.getScore() != null && row.getScore().getNum_solved() != null
            ? row.getScore().getNum_solved() : 0;
        int penalty = row.getScore() != null && row.getScore().getTotal_time() != null
            ? row.getScore().getTotal_time() : 0;

        return new Result(competitor.userId(), competitor.handle(), true, solved, penalty,
            (double) solved, detailOf(row));
    }

    private String detailOf(DjModels.Row row) {
        if (row.getProblems() == null) return null;
        Map<String, Object> detail = new LinkedHashMap<>();
        for (DjModels.Problem problem : row.getProblems()) {
            String label = StringUtils.hasText(problem.getLabel())
                ? problem.getLabel() : problem.getProblem_id();
            if (label == null) continue;
            detail.put(label, Map.of(
                "solved", Boolean.TRUE.equals(problem.getSolved()),
                "attempts", problem.getNum_judged() == null ? 0 : problem.getNum_judged(),
                "minute", problem.getTime() == null ? -1 : problem.getTime()
            ));
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            log.debug("Could not serialise DOMjudge detail: {}", e.getMessage());
            return null;
        }
    }

    /** Case and surrounding whitespace should never decide whether someone is on the board. */
    private String normalise(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
