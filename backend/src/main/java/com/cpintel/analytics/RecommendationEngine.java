package com.cpintel.analytics;

import com.cpintel.entity.ContestSummary;
import com.cpintel.entity.PlatformAccount;
import com.cpintel.entity.Recommendation;
import com.cpintel.entity.RevisionSchedule;
import com.cpintel.entity.TopicMastery;
import com.cpintel.entity.User;
import com.cpintel.integration.codeforces.CfUpcomingContests;
import com.cpintel.repository.jpa.ContestSummaryRepository;
import com.cpintel.repository.jpa.PlatformAccountRepository;
import com.cpintel.repository.jpa.RecommendationRepository;
import com.cpintel.repository.jpa.RevisionScheduleRepository;
import com.cpintel.repository.jpa.TopicMasteryRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.roadmap.ProblemRecommender;
import com.cpintel.roadmap.RoadmapTaxonomy;
import com.cpintel.roadmap.SkillAttribution;
import com.cpintel.service.RoadmapService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds and stores the practice sheets - daily, weekly and contest preparation - and keeps the
 * spaced-repetition revision schedule. What goes on a sheet is {@link PracticePlanner}'s
 * decision; this gathers what it needs and writes down what it returns.
 *
 * <h2>History</h2>
 *
 * <p>The Oracle version assembled its JSON by concatenating strings and listed topic names with
 * a "target difficulty" of about 250, which matched no problem that exists. The sheets then
 * gained real problems but passed an empty solved set, so problems the user had solved were
 * offered again as new. The weekly plan once ranked backwards (see {@link PracticePlanner}).
 *
 * <h2>Sheets are kept, not replaced</h2>
 *
 * <p>Every analytics refresh - each sync, each Gauntlet run - used to regenerate both sheets, so
 * a user who solved two of today's four problems and synced was handed four new ones and lost
 * their progress. A live sheet now stays until it expires (tomorrow 06:00 UTC, or a week) and
 * a refresh only marks what has since been solved. An empty sheet is rebuilt at once, since a
 * new user's first sync is exactly when there is something to put on it.
 *
 * <h2>The revision queue was always empty</h2>
 *
 * <p>Every generation set {@code next_revision_at} to now plus the interval - at least a day
 * away - for every faded skill, and the revision page regenerated whenever nothing was due. So
 * nothing was ever due, and "done" was undone by the next refresh. Schedules are now set when a
 * skill first fades (due at once) and afterwards only moved by the user marking a revision done.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecommendationEngine {

    public static final String DAILY = "DAILY";
    public static final String WEEKLY = "WEEKLY";
    public static final String CONTEST_PREP = "CONTEST_PREP";

    /** Daily sheets expire the following morning at this hour, UTC. */
    private static final int DAILY_EXPIRY_HOUR_UTC = 6;

    private static final Duration WEEKLY_LIFETIME = Duration.ofDays(7);

    /** Contest preparation is rebuilt at least daily, and as soon as its round starts. */
    private static final Duration CONTEST_PREP_LIFETIME = Duration.ofHours(24);

    /** Rated rounds the contest record is taken over. */
    private static final int RECENT_CONTESTS = 5;

    /** The aim for a user with no rating and nothing solved: Codeforces' entry level. */
    static final int DEFAULT_RATING = 800;

    /** An unrated user's estimate is this percentile of the ratings they have solved. */
    private static final double ESTIMATE_PERCENTILE = 0.7;

    private static final String CODEFORCES = "CODEFORCES";

    private final TopicMasteryRepository topicMasteryRepository;
    private final RecommendationRepository recommendationRepository;
    private final RevisionScheduleRepository revisionScheduleRepository;
    private final UserRepository userRepository;
    private final PlatformAccountRepository platformAccountRepository;
    private final ContestSummaryRepository contestSummaryRepository;
    private final ProblemRecommender problemRecommender;
    private final PracticeHistory practiceHistory;
    private final RoadmapService roadmapService;
    private final CfUpcomingContests upcomingContests;
    private final PlatformTransactionManager transactions;
    private final PlanCodec codec;

    /** What the user has solved on Codeforces, once per generation. */
    private record History(Set<String> solved, Map<String, Instant> solvedAt, List<Integer> solvedRatings) {}

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

    // -- sheets ------------------------------------------------------------

    /** A new daily sheet, superseding any still live. */
    @Transactional
    public void generateDailySheet(Long userId) {
        Instant now = Instant.now();
        PracticePlanner.Inputs in = inputs(userId, history(userId), liveKeys(userId, WEEKLY, CONTEST_PREP), now);
        store(userId, DAILY, planner().daily(in), tomorrowMorningUtc(now));
    }

    /** A new weekly plan, superseding any still live. */
    @Transactional
    public void generateWeeklySheet(Long userId) {
        Instant now = Instant.now();
        PracticePlanner.Inputs in = inputs(userId, history(userId), liveKeys(userId, DAILY, CONTEST_PREP), now);
        store(userId, WEEKLY, planner().weekly(in), now.plus(WEEKLY_LIFETIME));
    }

    /**
     * New contest preparation, superseding any still live.
     *
     * <p>Not one transaction: finding the next round can mean a call to Codeforces, and that
     * should not hold a database connection while it waits. The rating is read first, the round
     * looked up, and only the build and the write are transactional.
     */
    public void generateContestPrep(Long userId) {
        Instant now = Instant.now();
        History h = history(userId);
        Optional<Integer> cfRating = platformAccountRepository
            .findByUserUserIdAndPlatform(userId, CODEFORCES)
            .map(PlatformAccount::getCurrentRating)
            .filter(r -> r != null && r > 0);
        int rating = cfRating.orElseGet(() -> estimateRating(h.solvedRatings()));
        String ratingBasis = cfRating.isPresent() ? Plan.MEASURED : Plan.INFERRED;

        Plan.Contest contest = upcomingContests.nextFor(cfRating.orElse(null), now).orElse(null);
        Plan.ContestRecord record = contestRecord(userId);

        Instant expires = now.plus(CONTEST_PREP_LIFETIME);
        if (contest != null && contest.startsAt().isBefore(expires)) expires = contest.startsAt();
        Instant expiresAt = expires;

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            PracticePlanner.Inputs in = inputs(userId, h, liveKeys(userId, DAILY, WEEKLY), now);
            store(userId, CONTEST_PREP,
                planner().contestPrep(in, rating, ratingBasis, contest, record), expiresAt);
        });
    }

    /**
     * After new evidence: rebuild an empty or missing daily or weekly sheet, otherwise only mark
     * what has since been solved, and retire contest preparation so it is rebuilt on next view.
     */
    @Transactional
    public void refreshSheets(Long userId) {
        History h = history(userId);
        for (String type : List.of(DAILY, WEEKLY)) {
            Optional<Recommendation> live = recommendationRepository.findLatestActiveByUserAndType(userId, type);
            List<Plan.Item> items = live.map(r -> codec.readItems(r.getProblemList())).orElse(List.of());
            if (items.isEmpty()) {
                if (DAILY.equals(type)) generateDailySheet(userId);
                else generateWeeklySheet(userId);
                continue;
            }
            List<Plan.Item> marked = markSolved(items, h.solved());
            if (!marked.equals(items)) {
                live.get().setProblemList(codec.write(marked));
                recommendationRepository.save(live.get());
            }
        }
        recommendationRepository.markConsumed(userId, CONTEST_PREP);
    }

    // -- revision ----------------------------------------------------------

    /**
     * Puts every skill that has faded past the threshold on the revision schedule, due now.
     *
     * <p>A skill already scheduled keeps its date: that date is the spaced-repetition interval
     * from the user's last revision, and only marking a revision done moves it. Its decay and
     * priority are brought up to date so the queue still sorts by how far it has faded.
     */
    @Transactional
    public void generateRevisionSchedule(Long userId) {
        List<TopicMastery> faded = topicMasteryRepository.findDecayed(
            userId, TopicMastery.Scope.NODE, PracticePlanner.REVISION_DECAY_THRESHOLD);
        if (faded.isEmpty()) {
            log.debug("Nothing has faded for user {}", userId);
            return;
        }

        Map<String, RevisionSchedule> existing = new HashMap<>();
        for (RevisionSchedule rs : revisionScheduleRepository.findByUserUserId(userId)) {
            existing.put(rs.getTopic(), rs);
        }

        User user = userRepository.getReferenceById(userId);
        Instant now = Instant.now();
        List<RevisionSchedule> toSave = new ArrayList<>();
        for (TopicMastery tm : faded) {
            double decay = score(tm.getDecayScore());
            RevisionSchedule rs = existing.get(tm.getTopic());
            if (rs == null) {
                double ease = ScoringFormulas.easeFactor(decay);
                rs = RevisionSchedule.builder()
                    .user(user)
                    .topic(tm.getTopic())
                    .nextRevisionAt(now)
                    .intervalDays(ScoringFormulas.revisionIntervalDays(score(tm.getMasteryScore()), ease))
                    .easeFactor(ease)
                    .build();
            }
            rs.setDecayScore(decay);
            rs.setRevisionPriority((int) Math.round(decay));
            toSave.add(rs);
        }

        revisionScheduleRepository.saveAll(toSave);
        log.debug("Revision schedule for user {}: {} faded skills", userId, toSave.size());
    }

    /** What to solve to revise each of these skills: old solves again, then one new problem. */
    @Transactional(readOnly = true)
    public Map<String, List<Plan.Problem>> revisionProblems(Long userId, List<String> nodeKeys) {
        if (nodeKeys.isEmpty()) return Map.of();
        History h = history(userId);
        Map<String, PracticePlanner.Skill> skills = new HashMap<>();
        for (PracticePlanner.Skill s : skills(userId)) skills.put(s.nodeKey(), s);

        PracticePlanner planner = planner();
        Map<String, List<Plan.Problem>> out = new HashMap<>();
        for (String key : nodeKeys) {
            out.put(key, planner.revision(key, skills.get(key), h.solved(), h.solvedAt()));
        }
        return out;
    }

    // -- internals ---------------------------------------------------------

    private PracticePlanner planner() {
        return new PracticePlanner((def, skillRating, mastery, solved) ->
            problemRecommender.forNode(def, skillRating, mastery, solved, Integer.MAX_VALUE));
    }

    private PracticePlanner.Inputs inputs(Long userId, History h, Set<String> exclude, Instant now) {
        List<String> roadmapNext = roadmapService.nextUp(userId).stream()
            .map(RoadmapService.RoadmapNodeView::nodeKey)
            .toList();
        return new PracticePlanner.Inputs(skills(userId), roadmapNext, h.solved(), exclude,
            problemRecommender.problemsetAvailable(), now);
    }

    private List<PracticePlanner.Skill> skills(Long userId) {
        return topicMasteryRepository.findByUserUserIdAndScope(userId, TopicMastery.Scope.NODE).stream()
            .map(tm -> new PracticePlanner.Skill(tm.getTopic(), score(tm.getMasteryScore()),
                score(tm.getDecayScore()),
                tm.getProblemsAttempted() == null ? 0 : tm.getProblemsAttempted(),
                tm.getSkillRating(), tm.getLastPracticedAt()))
            .toList();
    }

    private History history(Long userId) {
        Set<String> solved = new HashSet<>();
        Map<String, Instant> solvedAt = new HashMap<>();
        List<Integer> ratings = new ArrayList<>();
        for (SkillAttribution.Practised p : practiceHistory.forUser(userId)) {
            if (!p.solved() || !CODEFORCES.equals(p.platform())) continue;
            solved.add(p.problemKey());
            if (p.lastAttemptAt() != null) solvedAt.put(p.problemKey(), p.lastAttemptAt());
            if (p.rating() != null) ratings.add(p.rating());
        }
        return new History(Set.copyOf(solved), Map.copyOf(solvedAt), ratings);
    }

    /** Problem keys on the live sheets of these types, so a new sheet does not repeat them. */
    private Set<String> liveKeys(Long userId, String... types) {
        Set<String> keys = new HashSet<>();
        for (String type : types) {
            recommendationRepository.findLatestActiveByUserAndType(userId, type).ifPresent(r -> {
                for (Plan.Item item : codec.readItems(r.getProblemList())) {
                    if (item.problems() == null) continue;
                    for (Plan.Problem p : item.problems()) keys.add(p.key());
                }
            });
        }
        return keys;
    }

    private static List<Plan.Item> markSolved(List<Plan.Item> items, Set<String> solved) {
        List<Plan.Item> out = new ArrayList<>(items.size());
        for (Plan.Item item : items) {
            if (item.problems() == null) {
                out.add(item);
                continue;
            }
            out.add(item.withProblems(item.problems().stream()
                .map(p -> p.withSolved(p.solved() || solved.contains(p.key())))
                .toList()));
        }
        return out;
    }

    /**
     * An unrated user's level, from what they have solved: high enough that the core problems
     * are ones they can do, which a median of mostly-easy history would undershoot.
     */
    static int estimateRating(List<Integer> solvedRatings) {
        if (solvedRatings == null || solvedRatings.isEmpty()) return DEFAULT_RATING;
        List<Integer> sorted = solvedRatings.stream().sorted().toList();
        int at = sorted.get((int) Math.floor(ESTIMATE_PERCENTILE * (sorted.size() - 1)));
        int rounded = (int) Math.round(at / 100.0) * 100;
        return Math.max(DEFAULT_RATING, Math.min(3500, rounded));
    }

    private Plan.ContestRecord contestRecord(Long userId) {
        List<ContestSummary> recent = contestSummaryRepository.findRecentContests(userId, 4 * RECENT_CONTESTS)
            .stream()
            .filter(c -> CODEFORCES.equals(c.getPlatform()))
            .limit(RECENT_CONTESTS)
            .toList();
        if (recent.isEmpty()) return null;
        double solved = recent.stream()
            .mapToInt(c -> c.getProblemsSolved() == null ? 0 : c.getProblemsSolved()).average().orElse(0);
        double change = recent.stream()
            .mapToInt(c -> c.getRatingChange() == null ? 0 : c.getRatingChange()).average().orElse(0);
        return new Plan.ContestRecord(recent.size(), solved, change);
    }

    /** Retires any live sheet of this type and saves the new one in its place. */
    private void store(Long userId, String type, PracticePlanner.Sheet sheet, Instant expiresAt) {
        recommendationRepository.markConsumed(userId, type);
        recommendationRepository.save(Recommendation.builder()
            .user(userRepository.getReferenceById(userId))
            .recType(type)
            .problemList(codec.write(sheet.items()))
            .metadata(codec.write(sheet.summary()))
            .generatedAt(Instant.now())
            .expiresAt(expiresAt)
            .isConsumed(false)
            .build());
    }

    /** Tomorrow at 06:00 UTC. */
    private static Instant tomorrowMorningUtc(Instant now) {
        return LocalDate.ofInstant(now, ZoneOffset.UTC)
            .plusDays(1)
            .atTime(DAILY_EXPIRY_HOUR_UTC, 0)
            .toInstant(ZoneOffset.UTC);
    }

    private static double score(Double value) {
        return value == null ? 0.0 : value;
    }
}
