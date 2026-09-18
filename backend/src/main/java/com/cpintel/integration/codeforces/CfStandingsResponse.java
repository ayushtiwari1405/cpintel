package com.cpintel.integration.codeforces;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * Response shape of the read-only {@code contest.standings} endpoint.
 *
 * One call carries three things the compete page needs — the contest object (phase, start
 * time, duration, freeze state), the problem list, and the requested handle's ranklist row —
 * so the page can be driven from a single request rather than three.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class CfStandingsResponse {

    private String status;
    private String comment;
    private Result result;

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Result {
        private Contest contest;
        private List<Problem> problems;
        private List<RanklistRow> rows;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Contest {
        private Integer id;
        private String name;
        private String type;
        /** BEFORE, CODING, PENDING_SYSTEM_TEST, SYSTEM_TEST or FINISHED. */
        private String phase;
        private Boolean frozen;
        private Long durationSeconds;
        private Long startTimeSeconds;
        private Long relativeTimeSeconds;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Problem {
        private Integer contestId;
        private String index;
        private String name;
        private String type;
        private Double points;
        private Integer rating;
        private List<String> tags;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RanklistRow {
        private Party party;
        private Integer rank;
        private Double points;
        private Integer penalty;
        private Integer successfulHackCount;
        private Integer unsuccessfulHackCount;
        private List<ProblemResult> problemResults;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Party {
        private List<Member> members;
        private String participantType;
        private String teamName;
        private Boolean ghost;
        private Integer room;
        private Long startTimeSeconds;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Member {
        private String handle;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProblemResult {
        private Double points;
        private Integer penalty;
        private Integer rejectedAttemptCount;
        /** PRELIMINARY while the contest is running, FINAL after system tests. */
        private String type;
        private Long bestSubmissionTimeSeconds;
    }
}
