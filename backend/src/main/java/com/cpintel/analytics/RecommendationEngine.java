package com.cpintel.analytics;

import com.cpintel.entity.Recommendation;
import com.cpintel.entity.RevisionSchedule;
import com.cpintel.entity.TopicMastery;
import com.cpintel.entity.User;
import com.cpintel.repository.jpa.RecommendationRepository;
import com.cpintel.repository.jpa.RevisionScheduleRepository;
import com.cpintel.repository.jpa.TopicMasteryRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.roadmap.ProblemRecommender;
import com.cpintel.roadmap.RoadmapTaxonomy;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Daily and weekly practice sheets, and the spaced-repetition revision schedule.
 *
 * <p>The Oracle version assembled its JSON by concatenating strings, so any topic name
 * containing a quote or backslash produced a payload the reader could not parse. These build
 * real objects and let Jackson serialise them.
 *
 * <h2>What was wrong with the sheets</h2>
 *
 * <p><b>They recommended nothing to solve.</b> Each item was a topic name and a
 * {@code targetDifficulty} computed as {@code round(mastery) + 200} - a number around 250,
 * which is not a Codeforces rating and corresponds to no problem that exists. The column
 * holding them is called {@code problem_list} and held no problems. Every item now carries
 * real, currently-unsolved problems with a link into the practice workspace.
 *
 * <p><b>The weekly plan ranked backwards.</b> It sorted by {@code mastery + decay} ascending
 * and took the lowest seven, which sends a strong-but-fading skill - high mastery, high decay,
 * so a high sum - to the <em>bottom</em> of the list and drops it. The comment above it said the
 * opposite was intended. Ranking is now by what the user actually retains, so a faded skill and
 * a weak one both surface.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecommendationEngine {

    /** How many skills go into a daily sheet. */
    private static final int DAILY_NODE_COUNT = 3;

    /** How many skills go into a weekly plan. */
    private static final int WEEKLY_NODE_COUNT = 7;

    /** Problems attached to each skill on a sheet. */
    private static final int PROBLEMS_PER_ITEM = 4;

    /** Below this decay a skill is still fresh enough to skip revision. */
    private static final double REVISION_DECAY_THRESHOLD = 10.0;

    /** Daily sheets expire the following morning at this hour, UTC. */
    private static final int DAILY_EXPIRY_HOUR_UTC = 6;

    /**
     * A skill needs at least this much evidence before a sheet will name it.
     *
     * <p>Without it every sheet is the same three untouched nodes from the deep end of the tree,
     * because a node with no attempts scores zero mastery and therefore sorts first among the
     * weakest. Those are not the user's weak areas; they are the parts of the tree they have not
     * reached. The roadmap is where you go to find those deliberately.
     */
    private static final int MIN_ATTEMPTS_FOR_SHEET = 3;

    private final TopicMasteryRepository topicMasteryRepository;
    private final RecommendationRepository recommendationRepository;
    private final RevisionScheduleRepository revisionScheduleRepository;
    private final UserRepository userRepository;
    private final ProblemRecommender problemRecommender;
    private final ObjectMapper objectMapper;

    /** How well a problem of the given rating suits this user's level in a skill-tree node. */
    @Transactional(readOnly = true)
    public double scoreProblemFit(Long userId, String nodeKey, Integer problemRating) {
        RoadmapTaxonomy.NodeDef def = RoadmapTaxonomy.byId(nodeKey);
        if (def == null) return 0.0;
        double mastery = topicMasteryRepository.findByUserUserIdAndTopic(userId, nodeKey)
            .map(tm -> tm.getMasteryScore() == null ? 0.0 : tm.getMasteryScore())
            .orElse(0.0);
        return ScoringFormulas.scoreProblemFit(
            mastery, def.minRating(), def.maxRating(), problemRating);
    }

    /**
     * Today's sheet: the weakest skills the user has actually started, with problems to solve
     * in each. Supersedes any sheet still live.
     */
    @Transactional
    public void generateDailySheet(Long userId) {
        List<TopicMastery> targets = sheetCandidates(userId).stream()
            .sorted(Comparator.comparingDouble(tm -> score(tm.getMasteryScore())))
            .limit(DAILY_NODE_COUNT)
            .toList();

        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> solved = Set.of();
        for (TopicMastery tm : targets) {
            RoadmapTaxonomy.NodeDef def = RoadmapTaxonomy.byId(tm.getTopic());
            if (def == null) continue;
            double mastery = score(tm.getMasteryScore());
            items.add(item(def, tm, mastery,
                "Weakest area you have started - mastery " + Math.round(mastery) + "%",
                solved));
        }

        supersede(userId, "DAILY");
        save(userId, "DAILY", items, tomorrowMorningUtc());
    }

    /**
     * This week's plan: the skills most in need of attention, ranked by what the user currently
     * retains rather than by what they once reached.
     *
     * <p>Ranking on {@code revisionScore} - mastery after decay - is what makes a strong skill
     * that has gone stale rank alongside a genuinely weak one, which is what the old comment
     * claimed and the old arithmetic did the reverse of.
     */
    @Transactional
    public void generateWeeklySheet(Long userId) {
        List<TopicMastery> targets = sheetCandidates(userId).stream()
            .sorted(Comparator.comparingDouble(tm -> retained(tm)))
            .limit(WEEKLY_NODE_COUNT)
            .toList();

        List<Map<String, Object>> items = new ArrayList<>();
        for (TopicMastery tm : targets) {
            RoadmapTaxonomy.NodeDef def = RoadmapTaxonomy.byId(tm.getTopic());
            if (def == null) continue;
            double mastery = score(tm.getMasteryScore());
            double decay = score(tm.getDecayScore());
            String priority = decay > 20 ? "REVISION" : mastery < 30 ? "NEW" : "PRACTICE";
            String reason = switch (priority) {
                case "REVISION" -> "Fading - " + Math.round(decay) + "% of this has decayed";
                case "NEW" -> "Barely started - mastery " + Math.round(mastery) + "%";
                default -> "Worth consolidating - mastery " + Math.round(mastery) + "%";
            };
            Map<String, Object> entry = item(def, tm, mastery, reason, Set.of());
            entry.put("priority", priority);
            items.add(entry);
        }

        supersede(userId, "WEEKLY");
        save(userId, "WEEKLY", items, Instant.now().plus(Duration.ofDays(7)));
    }

    /**
     * Refresh the spaced-repetition queue for every skill that has decayed past the threshold,
     * using an SM-2 derived interval: the more a skill has faded, the lower its ease factor and
     * the sooner it comes back around.
     *
     * <p>This produced nothing at all for as long as it existed. Decay is a function of how long
     * it has been since the skill was practised, and that timestamp was overwritten with the
     * current time on every analytics refresh - so decay was always zero, the
     * {@code decay > threshold} filter never matched, and the queue was permanently empty. The
     * timestamp is now the real date of the user's last work on the skill.
     */
    @Transactional
    public void generateRevisionSchedule(Long userId) {
        List<TopicMastery> due = topicMasteryRepository.findDecayed(
            userId, TopicMastery.Scope.NODE, REVISION_DECAY_THRESHOLD);

        if (due.isEmpty()) {
            log.debug("Nothing due for revision for user {}", userId);
            return;
        }

        User user = userRepository.getReferenceById(userId);
        List<RevisionSchedule> toSave = new ArrayList<>();

        for (TopicMastery tm : due) {
            double decay = score(tm.getDecayScore());
            double mastery = score(tm.getMasteryScore());
            double ease = ScoringFormulas.easeFactor(decay);
            int intervalDays = ScoringFormulas.revisionIntervalDays(mastery, ease);

            RevisionSchedule rs = revisionScheduleRepository
                .findByUserUserIdAndTopic(userId, tm.getTopic())
                .orElseGet(() -> RevisionSchedule.builder()
                    .user(user)
                    .topic(tm.getTopic())
                    .build());

            rs.setNextRevisionAt(Instant.now().plus(Duration.ofDays(intervalDays)));
            rs.setDecayScore(decay);
            rs.setRevisionPriority((int) Math.round(decay));
            rs.setIntervalDays(intervalDays);
            rs.setEaseFactor(ease);
            toSave.add(rs);
        }

        revisionScheduleRepository.saveAll(toSave);
        log.debug("Refreshed {} revision entries for user {}", toSave.size(), userId);
    }

    // -- internals ---------------------------------------------------------

    /**
     * Node rows with enough evidence to be worth ranking. Sheets work over nodes rather than the
     * coarse roll-ups so that "practise this" names something specific enough to act on -
     * "Bitmask DP" rather than "Dynamic Programming".
     */
    private List<TopicMastery> sheetCandidates(Long userId) {
        return topicMasteryRepository
            .findByUserUserIdAndScope(userId, TopicMastery.Scope.NODE).stream()
            .filter(tm -> orZeroInt(tm.getProblemsAttempted()) >= MIN_ATTEMPTS_FOR_SHEET)
            .filter(tm -> RoadmapTaxonomy.byId(tm.getTopic()) != null)
            .toList();
    }

    /** One sheet entry, with real problems attached. */
    private Map<String, Object> item(RoadmapTaxonomy.NodeDef def, TopicMastery tm,
                                     double mastery, String reason, Set<String> solvedKeys) {
        List<ProblemRecommender.Recommended> problems =
            problemRecommender.forNode(def, mastery, solvedKeys, PROBLEMS_PER_ITEM);

        List<Map<String, Object>> problemPayload = new ArrayList<>(problems.size());
        for (ProblemRecommender.Recommended p : problems) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("contestId", p.contestId());
            row.put("index", p.index());
            row.put("name", p.name());
            row.put("rating", p.rating());
            row.put("solved", p.solved());
            row.put("fit", p.fit());
            row.put("practicePath", p.practicePath());
            row.put("judgeUrl", p.judgeUrl());
            problemPayload.add(row);
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("nodeKey", def.id());
        item.put("title", def.displayName());
        item.put("track", def.track());
        item.put("topic", def.rollupTopic());
        item.put("blurb", def.blurb());
        item.put("masteryScore", Math.round(mastery));
        item.put("decayScore", Math.round(score(tm.getDecayScore())));
        // A real Codeforces rating this time, derived from where the user sits inside the
        // node's own difficulty band rather than from the mastery percentage directly.
        item.put("targetRating", (int) Math.round(
            ScoringFormulas.targetRating(mastery, def.minRating(), def.maxRating())));
        item.put("reason", reason);
        item.put("problems", problemPayload);
        return item;
    }

    /** Mark any live sheet of this type consumed, so the new one is the only active row. */
    private void supersede(Long userId, String type) {
        recommendationRepository.markConsumed(userId, type);
    }

    private void save(Long userId, String type, List<Map<String, Object>> items, Instant expiresAt) {
        recommendationRepository.save(Recommendation.builder()
            .user(userRepository.getReferenceById(userId))
            .recType(type)
            .problemList(toJson(items))
            .generatedAt(Instant.now())
            .expiresAt(expiresAt)
            .isConsumed(false)
            .build());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialise recommendation payload: {}", e.getMessage());
            return "[]";
        }
    }

    /** Tomorrow at 06:00 UTC. */
    private Instant tomorrowMorningUtc() {
        return LocalDate.now(ZoneOffset.UTC)
            .plusDays(1)
            .atTime(DAILY_EXPIRY_HOUR_UTC, 0)
            .toInstant(ZoneOffset.UTC);
    }

    /** Mastery after decay: what the user still has. */
    private double retained(TopicMastery tm) {
        Double stored = tm.getRevisionScore();
        if (stored != null) return stored;
        return ScoringFormulas.revisionScore(score(tm.getMasteryScore()), score(tm.getDecayScore()));
    }

    private double score(Double value) {
        return value == null ? 0.0 : value;
    }

    private int orZeroInt(Integer value) {
        return value == null ? 0 : value;
    }
}
