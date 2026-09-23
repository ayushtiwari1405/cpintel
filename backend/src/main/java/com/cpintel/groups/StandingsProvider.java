package com.cpintel.groups;

import com.cpintel.entity.GroupContest;

import java.util.List;

/**
 * Reads a group's results off whichever judge is actually running the contest.
 *
 * The interface takes the competitors rather than returning the whole board, because that is
 * the shape both judges reward. On Codeforces the only permitted way to read a non-gym contest
 * per-handle costs one rate-limited call each, so asking about thirty people is thirty calls
 * and asking about everybody is an 8 MB download of eleven thousand rows nobody wanted. On
 * DOMjudge the whole board arrives at once and is filtered locally. Either way the caller only
 * ever wants the subset.
 */
public interface StandingsProvider {

    /** CODEFORCES or DOMJUDGE — matched against {@link GroupContest#getPlatform()}. */
    String platform();

    /**
     * One person to look for, and the name to look for them under.
     *
     * A null or blank handle means the group has no way to identify them on this judge, which
     * is a configuration problem rather than a zero score, and is reported as unmatched.
     */
    /**
     * One person to look up on the judge.
     *
     * <p>{@code handle} is what the judge knows them as, matched by name — a Codeforces handle,
     * or a DOMjudge team name an admin typed. {@code teamId} is an <em>exact</em> DOMjudge team
     * id and is null everywhere else.
     *
     * <p>Both exist because name matching is a guess and an id is not. Where an admin has
     * attached a DOMjudge account, the team is known exactly and there is no reason to go back
     * to comparing strings — which is the step that turns a renamed team into a member who
     * silently drops off the board.
     */
    record Competitor(Long userId, String handle, String teamId) {}

    /**
     * What the judge says about one competitor.
     *
     * `found` separates "sat the contest and solved nothing" from "we could not find this
     * person at all". Collapsing those two into a zero is how a mistyped handle turns into a
     * confident last place.
     */
    record Result(
        Long userId,
        String handle,
        boolean found,
        int solved,
        int penalty,
        Double score,
        /** Per-problem detail as JSON, shaped by the judge it came from. */
        String detail
    ) {}

    /**
     * Fetches results for these competitors.
     *
     * Implementations must return one entry per competitor, in any order, and must not throw
     * for a single competitor that cannot be read — an unreachable handle is a `found=false`
     * row, not a failed refresh for the whole group.
     */
    List<Result> fetch(GroupContest contest, List<Competitor> competitors);
}
