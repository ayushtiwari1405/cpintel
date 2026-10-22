package com.cpintel.analytics;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * What a generated practice plan looks like, both on the wire and in the recommendations table.
 *
 * <p>Sheets were stored and served as lists of string-keyed maps, so nothing said what a sheet
 * held and a renamed key failed silently on the page. These records keep the old field names, so
 * rows written before they existed still read back.
 */
public final class Plan {

    private Plan() {}

    /** Built from the user's own history. */
    public static final String MEASURED = "MEASURED";
    /** A fallback (the roadmap's next skills, a default rating) because there is too little history. */
    public static final String INFERRED = "INFERRED";
    /** Some of each. */
    public static final String PARTIAL = "PARTIAL";
    /** Nothing to plan from at all. */
    public static final String NONE = "NONE";

    /** Why a problem sits where it does on an item, easiest first. */
    public static final String WARM_UP = "WARM_UP";
    public static final String CORE = "CORE";
    public static final String STRETCH = "STRETCH";
    /** Solved before and long ago: solve it again from scratch. */
    public static final String RESOLVE = "RESOLVE";

    /** One problem to solve. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Problem(
        Integer contestId,
        String index,
        String name,
        Integer rating,
        boolean solved,
        double fit,
        String role,
        String practicePath,
        String judgeUrl
    ) {
        @JsonIgnore
        public String key() {
            return contestId + index;
        }

        public Problem withSolved(boolean value) {
            return value == solved ? this
                : new Problem(contestId, index, name, rating, value, fit, role, practicePath, judgeUrl);
        }
    }

    /**
     * One skill on a sheet and the problems to solve in it.
     *
     * @param reasonCode WEAK_TOPIC, REVISION_DUE, MOMENTUM, PROGRESSION, NEXT_ON_ROADMAP or
     *                   CONTEST_STAPLE: the rule that put it here, for the page to label
     * @param basis      {@link #MEASURED} or {@link #INFERRED}
     * @param session    weekly plans only: which session of the week this is, from 1
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
        String nodeKey,
        String title,
        String track,
        String topic,
        String blurb,
        Long masteryScore,
        Long decayScore,
        Integer targetRating,
        String reason,
        String reasonCode,
        String priority,
        String basis,
        Integer session,
        List<Problem> problems
    ) {
        public Item withProblems(List<Problem> value) {
            return new Item(nodeKey, title, track, topic, blurb, masteryScore, decayScore,
                targetRating, reason, reasonCode, priority, basis, session, value);
        }
    }

    /**
     * What the sheet as a whole rests on, kept beside its items.
     *
     * @param goalProblems weekly plans: how many of the problems make a good week
     * @param rating       contest prep: the rating the plan is aimed at
     * @param ratingBasis  whether {@code rating} is the user's Codeforces rating or an estimate
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Summary(
        String basis,
        List<String> notes,
        Integer goalProblems,
        Integer rating,
        String ratingBasis,
        Contest contest,
        ContestRecord record
    ) {
        public static Summary of(String basis, List<String> notes) {
            return new Summary(basis, notes, null, null, null, null, null);
        }
    }

    /** An upcoming Codeforces round. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Contest(
        Integer id,
        String name,
        String division,
        Instant startsAt,
        Long durationSeconds,
        String url
    ) {}

    /** How the user has done in their recent rated rounds. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContestRecord(
        int contests,
        double avgSolved,
        double avgRatingChange
    ) {}
}
