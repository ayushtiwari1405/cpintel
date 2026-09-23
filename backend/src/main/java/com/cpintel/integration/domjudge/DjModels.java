package com.cpintel.integration.domjudge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * The slice of DOMjudge's v4 API this needs.
 *
 * Only the fields the group board and the compete arena read are declared; everything else is
 * ignored, which keeps this working across the DOMjudge versions that add fields between
 * releases. The scoreboard endpoint returns the whole board in one call, so unlike Codeforces
 * there is no per-competitor fan-out — the filtering happens here.
 *
 * <p>Two shapes in here regularly catch people out and are worth stating plainly:
 *
 * <ul>
 *   <li>{@link State} reports <em>timestamps, not booleans</em>. A contest is running when
 *       {@code started} is non-null and {@code ended} is null. A boolean field would have been
 *       the obvious guess and would have read every finished contest as still live.
 *   <li>Durations ({@code duration}, {@code contest_time}) are {@code "H:MM:SS.mmm"} strings
 *       rather than numbers — see {@link DjTime} for the parser.
 * </ul>
 */
public class DjModels {

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Team {
        private String id;
        private String name;
        /** The display name, which is what a scoreboard usually shows. */
        private String display_name;
    }

    /**
     * The account the current credentials belong to, from {@code /api/v4/user}.
     *
     * This is the keystone of the submit-as-the-contestant model. DOMjudge attributes a
     * submission to the <em>team</em> behind the authenticated account, never to the account
     * itself, so {@code team_id} here is the only thing that says where a contestant's
     * submissions will actually land. A null {@code team_id} means the login is real but has
     * no team — an admin or a jury account — and submitting as it would silently go nowhere,
     * which is why provisioning checks this field rather than merely checking that the
     * password works.
     *
     * <p>{@code roles} is read only to tell a team account apart from an admin one, so the
     * arena can explain which reads it is allowed to make rather than discovering it in a 403.
     */
    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class User {
        private String id;
        private String username;
        private String name;
        private String email;
        /**
         * The team's id — present from DOMjudge 8.2 onwards, absent before it.
         *
         * 8.0 reports only {@link #team}, the name. Reading just this one is what made every
         * team account on an 8.0 instance look like an admin account with no team, so both are
         * carried and the caller decides which it can use.
         */
        private String team_id;
        /** The team's name. Present on every version that reports a team at all. */
        private String team;
        private List<String> roles;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Scoreboard {
        private List<Row> rows;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Row {
        private Integer rank;
        private String team_id;
        private Score score;
        private List<Problem> problems;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Score {
        private Integer num_solved;
        /** Penalty minutes in ICPC scoring; absent in some configurations. */
        private Integer total_time;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Problem {
        private String problem_id;
        private String label;
        private Integer num_judged;
        private Boolean solved;
        private Integer time;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Contest {
        private String id;
        private String name;
        private String formal_name;
        private String start_time;
        private String end_time;
        /** "H:MM:SS.mmm". */
        private String duration;
        /** "H:MM:SS.mmm", or null when the board never freezes. */
        private String scoreboard_freeze_duration;
        private Integer penalty_time;
    }

    /**
     * Where the contest is in its life, as five nullable timestamps.
     *
     * Non-null means "this has happened", so {@code started != null && ended == null} is the
     * running case. DOMjudge exposes no phase string; this endpoint is the authoritative
     * answer and is cheap enough to poll.
     */
    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class State {
        private String started;
        private String ended;
        private String frozen;
        private String thawed;
        private String finalized;
        private String end_of_updates;
    }

    /** A problem as the contest exposes it — label is the letter contestants see. */
    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContestProblem {
        private String id;
        private String label;
        private String name;
        private Integer ordinal;
        private String rgb;
        private String color;
        /** Seconds, as a decimal. */
        private Double time_limit;
        private Integer test_data_count;
        private String externalid;
    }

    /**
     * A submission language.
     *
     * {@code allow_submit} is absent on older builds, which is why the provider treats null as
     * permitted rather than forbidden — refusing every language on an older DOMjudge would be
     * a worse failure than offering one it then rejects with a clear message.
     */
    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Language {
        private String id;
        private String name;
        private List<String> extensions;
        private Boolean allow_submit;
        private Boolean allow_judge;
        private Boolean entry_point_required;
        private String entry_point_name;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Submission {
        private String id;
        private String team_id;
        private String problem_id;
        private String language_id;
        /** Absolute wall-clock time, ISO-8601 with offset. */
        private String time;
        /** "H:MM:SS.mmm" from the contest start. */
        private String contest_time;
        private String entry_point;
    }

    /**
     * The judge's answer to one submission.
     *
     * {@code judgement_type_id} is null while judging is still in progress, which is exactly
     * the "pending" state the editor's console renders as TESTING. {@code valid} false marks a
     * judgement superseded by a rejudge — those are ignored so a rejudged submission shows its
     * current verdict rather than its first one.
     */
    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Judgement {
        private String id;
        private String submission_id;
        private Boolean valid;
        private String judgement_type_id;
        private String start_time;
        private String end_time;
        private Double max_run_time;
    }

    /** What a verdict code means, and whether it counts as solved. */
    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JudgementType {
        private String id;
        private String name;
        private Boolean penalty;
        private Boolean solved;
    }

    /**
     * One test case of a problem, as the admin API reports it.
     *
     * Only the ones flagged {@code sample} are ever read — the rest are the secret data the
     * contest is judged on, and pulling those into a contestant-facing app would defeat the
     * judge entirely.
     */
    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Testcase {
        private String id;
        private Boolean sample;
        private Integer ordinal;
        private String description;
        /** Present on some builds; otherwise the content is fetched per-file. */
        private String input;
        private String output;
    }
}
