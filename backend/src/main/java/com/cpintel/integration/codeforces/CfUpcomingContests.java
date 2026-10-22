package com.cpintel.integration.codeforces;

import com.cpintel.analytics.Plan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The next rated Codeforces round a user can enter, for contest preparation.
 *
 * <p>{@code contest.list} is one call for every contest Codeforces has run, so it is held for
 * an hour and shared by every user. A failed call keeps whatever was held before and is not
 * retried for a few minutes, so an outage costs one slow request rather than one per page view;
 * with nothing held, preparation goes ahead without a contest and says so.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CfUpcomingContests {

    private static final Duration TTL = Duration.ofHours(1);
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(5);

    /** How far ahead a round still counts as the one to prepare for. */
    static final Duration HORIZON = Duration.ofDays(14);

    private final CodeforcesClient client;

    private volatile List<CfStandingsResponse.Contest> held = List.of();
    private volatile Instant fetchedAt = Instant.EPOCH;
    private volatile Instant failedAt = Instant.EPOCH;

    /**
     * The soonest round within {@link #HORIZON} whose division admits {@code rating}.
     *
     * @param rating the user's rating, or null for an unrated user (who may enter anything but
     *               Div. 1)
     */
    public Optional<Plan.Contest> nextFor(Integer rating, Instant now) {
        return contests(now).stream()
            .filter(c -> "BEFORE".equals(c.getPhase()) && c.getId() != null && c.getStartTimeSeconds() != null)
            .filter(c -> {
                Instant start = Instant.ofEpochSecond(c.getStartTimeSeconds());
                return start.isAfter(now) && !start.isAfter(now.plus(HORIZON));
            })
            .filter(c -> admits(division(c.getName()), rating))
            .min(Comparator.comparingLong(CfStandingsResponse.Contest::getStartTimeSeconds)
                .thenComparingInt(CfStandingsResponse.Contest::getId))
            .map(c -> new Plan.Contest(c.getId(), c.getName(), division(c.getName()),
                Instant.ofEpochSecond(c.getStartTimeSeconds()), c.getDurationSeconds(),
                "https://codeforces.com/contests/" + c.getId()));
    }

    private synchronized List<CfStandingsResponse.Contest> contests(Instant now) {
        boolean fresh = now.isBefore(fetchedAt.plus(TTL));
        boolean backingOff = now.isBefore(failedAt.plus(RETRY_AFTER_FAILURE));
        if (!fresh && !backingOff) {
            try {
                held = List.copyOf(client.getContests());
                fetchedAt = now;
            } catch (Exception e) {
                failedAt = now;
                log.warn("Could not load the Codeforces contest list ({} held): {}",
                    held.size(), e.getMessage());
            }
        }
        return held;
    }

    /**
     * The division a round's name declares, or null for anything else (team rounds, April
     * Fools, sponsored one-offs) - those have rules of their own and are not offered.
     */
    static String division(String name) {
        if (name == null) return null;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("div. 1 + div. 2") || n.contains("div. 1 + 2") || n.contains("global round")) {
            return "Div. 1 + 2";
        }
        if (n.contains("educational")) return "Educational";
        if (n.contains("div. 1")) return "Div. 1";
        if (n.contains("div. 2")) return "Div. 2";
        if (n.contains("div. 3")) return "Div. 3";
        if (n.contains("div. 4")) return "Div. 4";
        return null;
    }

    /** Whether a round of this division is rated for someone at {@code rating}. */
    static boolean admits(String division, Integer rating) {
        if (division == null) return false;
        int r = rating == null ? 0 : rating;
        return switch (division) {
            case "Div. 1 + 2" -> true;
            case "Div. 1" -> r >= 1900;
            case "Div. 2", "Educational" -> r < 2100;
            case "Div. 3" -> r < 1600;
            case "Div. 4" -> r < 1400;
            default -> false;
        };
    }
}
