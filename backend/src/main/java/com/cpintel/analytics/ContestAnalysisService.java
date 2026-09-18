package com.cpintel.analytics;

import com.cpintel.entity.ContestSummary;
import com.cpintel.entity.Recommendation;
import com.cpintel.repository.jpa.ContestSummaryRepository;
import com.cpintel.repository.jpa.RecommendationRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-contest insights and cross-contest behaviour patterns. Replaces the pkg_contest
 * PL/SQL package.
 *
 * <p>Insight text is built the same way the Oracle version built it, but the JSON
 * wrapper is serialised by Jackson rather than concatenated, so an insight containing a
 * quote no longer produces an unparseable payload.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContestAnalysisService {

    /** Wrong submissions above which penalties are the headline problem. */
    private static final int HIGH_PENALTY_THRESHOLD = 3;

    /** Rating loss below which the contest counts as a significant drop. */
    private static final int SIGNIFICANT_DROP = -50;

    /** Rating gain above which the contest counts as a standout result. */
    private static final int STANDOUT_GAIN = 100;

    /** Minutes to first solve above which pace is the headline problem. */
    private static final int SLOW_FIRST_SOLVE_MINS = 30;

    /** Contests needed before behaviour patterns mean anything. */
    private static final int PATTERN_MIN_CONTESTS = 5;

    /** Average late-penalty score above which the pattern is worth reporting. */
    private static final double PATTERN_THRESHOLD = 1.5;

    private final ContestSummaryRepository contestSummaryRepository;
    private final RecommendationRepository recommendationRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    /**
     * Produce a single coaching insight for one contest and store it as a CONTEST_PREP
     * recommendation.
     *
     * <p>Note: this inserts a new row every time it is called for the same contest, as
     * the Oracle procedure did. Rows expire after 7 days but are not deduplicated, so
     * repeatedly re-analysing a contest accumulates near-identical recommendations.
     * Preserved as-is to keep the migration behaviour-for-behaviour; worth revisiting.
     */
    @Transactional
    public void analyzeContest(Long userId, Long contestId) {
        ContestSummary contest = contestSummaryRepository.findById(contestId).orElse(null);
        if (contest == null || !contest.getUser().getUserId().equals(userId)) return;

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("contest_id", contestId);
        metadata.put("insight", insightFor(contest));

        store(userId, metadata, Duration.ofDays(7));
    }

    /** Re-analyse every contest the user has. */
    @Transactional
    public void updateContestMetrics(Long userId) {
        List<ContestSummary> contests =
            contestSummaryRepository.findByUserUserIdOrderByContestDateDesc(userId);
        for (ContestSummary contest : contests) {
            analyzeContest(userId, contest.getContestId());
        }
    }

    /** Human-readable summary of one contest, or a message if it does not exist. */
    @Transactional(readOnly = true)
    public String generateContestSummary(Long contestId) {
        return contestSummaryRepository.findById(contestId)
            .map(c -> String.join("\n",
                "Platform: "      + c.getPlatform(),
                "Contest: "       + c.getContestName(),
                "Rank: "          + orNa(c.getRank()),
                "Rating change: " + orNa(c.getRatingChange()),
                "Solved: "        + c.getProblemsSolved() + "/" + c.getTotalProblems(),
                "Wrong subs: "    + c.getWrongSubmissions(),
                "First solve: "   + (c.getFirstSolveMins() == null
                                        ? "N/A" : c.getFirstSolveMins() + " min")))
            .orElse("Contest not found");
    }

    /**
     * Detect whether the user tends to rack up penalties on the problems they are slow
     * to reach — a proxy for pushing too hard on the back half of a contest.
     */
    @Transactional
    public void detectBehaviorPatterns(Long userId) {
        List<ContestSummary> contests =
            contestSummaryRepository.findByUserUserIdOrderByContestDateDesc(userId);
        if (contests.size() < PATTERN_MIN_CONTESTS) return;

        double avgLatePenalty = contests.stream()
            .mapToDouble(this::latePenaltyScore)
            .average()
            .orElse(0.0);

        if (avgLatePenalty <= PATTERN_THRESHOLD) return;

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("pattern", "late_penalty");
        metadata.put("message", "You tend to accumulate penalties in the latter portion "
            + "of contests. Consider slowing down on harder problems.");

        store(userId, metadata, Duration.ofDays(14));
    }

    // ── internals ──────────────────────────────────────────────────────────

    /**
     * A contest contributes its wrong-submission count only when it shows the pattern:
     * more than two wrong submissions, and a first solve late relative to the user's
     * own average pace in that contest. Everything else contributes zero.
     */
    private double latePenaltyScore(ContestSummary c) {
        Integer wrong = c.getWrongSubmissions();
        Integer first = c.getFirstSolveMins();
        Integer avg   = c.getAvgSolveMins();
        if (wrong == null || first == null || avg == null) return 0.0;
        return (wrong > 2 && first > avg * 0.7) ? wrong : 0.0;
    }

    /**
     * The single most useful thing to tell the user about this contest, picked in
     * priority order. Null-valued fields fall through, matching PL/SQL's three-valued
     * comparison semantics.
     */
    private String insightFor(ContestSummary c) {
        Integer wrong  = c.getWrongSubmissions();
        Integer change = c.getRatingChange();
        Integer first  = c.getFirstSolveMins();

        if (wrong != null && wrong > HIGH_PENALTY_THRESHOLD) {
            return "High penalty count (" + wrong + " wrong submissions). "
                 + "Focus on testing before submitting.";
        }
        if (change != null && change < SIGNIFICANT_DROP) {
            return "Significant rating drop. Review problems you could not solve.";
        }
        if (change != null && change > STANDOUT_GAIN) {
            return "Excellent performance! Rating gain of " + change + ".";
        }
        if (first != null && first > SLOW_FIRST_SOLVE_MINS) {
            return "Slow first solve at " + first + " mins. Practice easier problems faster.";
        }
        return "Solid contest. Solved " + c.getProblemsSolved() + "/" + c.getTotalProblems()
             + " problems.";
    }

    private void store(Long userId, Map<String, Object> metadata, Duration ttl) {
        recommendationRepository.save(Recommendation.builder()
            .user(userRepository.getReferenceById(userId))
            .recType("CONTEST_PREP")
            .metadata(toJson(metadata))
            .generatedAt(Instant.now())
            .expiresAt(Instant.now().plus(ttl))
            .isConsumed(false)
            .build());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialise contest metadata: {}", e.getMessage());
            return "{}";
        }
    }

    private String orNa(Integer value) {
        return value == null ? "N/A" : String.valueOf(value);
    }
}
