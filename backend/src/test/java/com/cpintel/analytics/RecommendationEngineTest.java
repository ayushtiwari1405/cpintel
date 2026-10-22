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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The engine's own duties: what it stores, when a schedule moves, and what a refresh keeps.
 * What goes on a sheet is {@link PracticePlannerTest}'s subject.
 */
class RecommendationEngineTest {

    private static final long USER = 42L;

    private TopicMasteryRepository mastery;
    private RecommendationRepository recommendations;
    private RevisionScheduleRepository revisions;
    private PlatformAccountRepository accounts;
    private ContestSummaryRepository contests;
    private ProblemRecommender problems;
    private PracticeHistory history;
    private RoadmapService roadmap;
    private CfUpcomingContests upcoming;
    private final PlanCodec codec = new PlanCodec(new ObjectMapper().findAndRegisterModules());
    private RecommendationEngine engine;

    private final RoadmapTaxonomy.NodeDef node = RoadmapTaxonomy.NODES.stream()
        .filter(n -> n.maxRating() - n.minRating() >= 400).findFirst().orElseThrow();
    private final int contestId = 1000 + RoadmapTaxonomy.NODES.indexOf(node);

    @BeforeEach
    void setUp() {
        mastery = mock(TopicMasteryRepository.class);
        recommendations = mock(RecommendationRepository.class);
        revisions = mock(RevisionScheduleRepository.class);
        UserRepository users = mock(UserRepository.class);
        accounts = mock(PlatformAccountRepository.class);
        contests = mock(ContestSummaryRepository.class);
        problems = mock(ProblemRecommender.class);
        history = mock(PracticeHistory.class);
        roadmap = mock(RoadmapService.class);
        upcoming = mock(CfUpcomingContests.class);
        engine = new RecommendationEngine(mastery, recommendations, revisions, users, accounts, contests,
            problems, history, roadmap, upcoming, mock(PlatformTransactionManager.class), codec);

        when(users.getReferenceById(USER)).thenReturn(User.builder().userId(USER).build());
        when(recommendations.findLatestActiveByUserAndType(eq(USER), anyString())).thenReturn(Optional.empty());
        when(roadmap.nextUp(USER)).thenReturn(List.of());
        when(problems.problemsetAvailable()).thenReturn(true);
        when(problems.forNode(any(), any(), anyDouble(), any(), anyInt())).thenAnswer(inv -> {
            RoadmapTaxonomy.NodeDef def = inv.getArgument(0);
            Set<String> solved = inv.getArgument(3);
            List<ProblemRecommender.Recommended> out = new ArrayList<>();
            for (int r = def.minRating(); r <= def.maxRating(); r += 100) {
                String index = "P" + r;
                out.add(new ProblemRecommender.Recommended(contestId, index, "n" + r, r, List.of(),
                    solved.contains(contestId + index), 50, "/practice", null));
            }
            return out;
        });
        when(mastery.findByUserUserIdAndScope(USER, TopicMastery.Scope.NODE)).thenReturn(List.of(
            TopicMastery.builder().topic(node.id()).scope(TopicMastery.Scope.NODE)
                .masteryScore(20.0).decayScore(0.0).problemsAttempted(6).build()));
        when(history.forUser(USER)).thenReturn(List.of());
    }

    private Recommendation saved(String type) {
        ArgumentCaptor<Recommendation> rec = ArgumentCaptor.forClass(Recommendation.class);
        verify(recommendations, atLeastOnce()).save(rec.capture());
        return rec.getAllValues().stream().filter(r -> type.equals(r.getRecType())).reduce((a, b) -> b).orElseThrow();
    }

    private String key(int rating) {
        return contestId + "P" + rating;
    }

    // -- sheets ------------------------------------------------------------------

    @Test
    @DisplayName("a generated sheet is stored with its summary, superseding the old one")
    void storesSheet() {
        engine.generateDailySheet(USER);

        verify(recommendations).markConsumed(USER, "DAILY");
        Recommendation rec = saved("DAILY");
        assertFalse(codec.readItems(rec.getProblemList()).isEmpty());
        assertEquals(Plan.MEASURED, codec.readSummary(rec.getMetadata()).basis());
        assertTrue(rec.getExpiresAt().isAfter(Instant.now()));
    }

    @Test
    @DisplayName("solved problems from the history are kept off a new sheet")
    void solvedFromHistory() {
        String solved = key(node.minRating() + 100);
        when(history.forUser(USER)).thenReturn(List.of(new SkillAttribution.Practised(
            "CODEFORCES", solved, node.minRating() + 100, Set.of(), true, Instant.now())));

        engine.generateDailySheet(USER);

        List<String> keys = codec.readItems(saved("DAILY").getProblemList()).stream()
            .flatMap(i -> i.problems().stream()).map(Plan.Problem::key).toList();
        assertFalse(keys.contains(solved));
    }

    @Test
    @DisplayName("a new daily sheet leaves out problems on the live weekly plan")
    void excludesLiveWeekly() {
        engine.generateWeeklySheet(USER);
        Recommendation weekly = saved("WEEKLY");
        List<String> weeklyKeys = codec.readItems(weekly.getProblemList()).stream()
            .flatMap(i -> i.problems().stream()).map(Plan.Problem::key).toList();
        assertFalse(weeklyKeys.isEmpty());
        when(recommendations.findLatestActiveByUserAndType(USER, "WEEKLY")).thenReturn(Optional.of(weekly));

        engine.generateDailySheet(USER);

        List<String> dailyKeys = codec.readItems(saved("DAILY").getProblemList()).stream()
            .flatMap(i -> i.problems().stream()).map(Plan.Problem::key).toList();
        for (String k : dailyKeys) assertFalse(weeklyKeys.contains(k), k + " is on both sheets");
    }

    @Test
    @DisplayName("a refresh keeps a live sheet and marks what has since been solved")
    void refreshKeepsLiveSheet() {
        Plan.Problem open = new Plan.Problem(contestId, "P" + node.minRating(), "x", node.minRating(),
            false, 50, Plan.CORE, "/p", null);
        Plan.Item item = new Plan.Item(node.id(), "t", null, null, null, 20L, 0L, 1000, "r", "WEAK_TOPIC",
            null, Plan.MEASURED, null, List.of(open));
        Recommendation live = Recommendation.builder().recType("DAILY")
            .problemList(codec.write(List.of(item))).generatedAt(Instant.now()).build();
        when(recommendations.findLatestActiveByUserAndType(USER, "DAILY")).thenReturn(Optional.of(live));
        when(history.forUser(USER)).thenReturn(List.of(new SkillAttribution.Practised(
            "CODEFORCES", open.key(), open.rating(), Set.of(), true, Instant.now())));

        engine.refreshSheets(USER);

        verify(recommendations, never()).markConsumed(USER, "DAILY");
        assertTrue(codec.readItems(live.getProblemList()).get(0).problems().get(0).solved());
        verify(recommendations).save(live);
        // The weekly plan was missing, so it was built; contest prep is retired for a rebuild.
        verify(recommendations).markConsumed(USER, "WEEKLY");
        verify(recommendations).markConsumed(USER, "CONTEST_PREP");
    }

    @Test
    @DisplayName("a refresh rebuilds a live sheet that has nothing on it")
    void refreshRebuildsEmpty() {
        Recommendation empty = Recommendation.builder().recType("DAILY").problemList("[]")
            .generatedAt(Instant.now()).build();
        when(recommendations.findLatestActiveByUserAndType(USER, "DAILY")).thenReturn(Optional.of(empty));

        engine.refreshSheets(USER);

        verify(recommendations).markConsumed(USER, "DAILY");
        assertFalse(codec.readItems(saved("DAILY").getProblemList()).isEmpty());
    }

    @Test
    @DisplayName("a refresh rebuilds a sheet built while the problem list was unavailable")
    void refreshFillsProblemlessSheet() {
        Plan.Item bare = new Plan.Item(node.id(), "t", null, null, null, 20L, 0L, 1000, "r", "WEAK_TOPIC",
            null, Plan.MEASURED, null, List.of());
        Recommendation live = Recommendation.builder().recType("DAILY")
            .problemList(codec.write(List.of(bare))).generatedAt(Instant.now()).build();
        when(recommendations.findLatestActiveByUserAndType(USER, "DAILY")).thenReturn(Optional.of(live));

        engine.refreshSheets(USER);

        verify(recommendations).markConsumed(USER, "DAILY");
        assertFalse(codec.readItems(saved("DAILY").getProblemList()).get(0).problems().isEmpty());
    }

    // -- contest preparation -----------------------------------------------------------

    @Test
    @DisplayName("contest prep aims at the Codeforces rating and expires when the round starts")
    void contestPrepMeasured() {
        int rating = node.minRating() + 200;
        when(accounts.findByUserUserIdAndPlatform(USER, "CODEFORCES"))
            .thenReturn(Optional.of(PlatformAccount.builder().currentRating(rating).build()));
        Instant start = Instant.now().plus(Duration.ofHours(5));
        Plan.Contest round = new Plan.Contest(2100, "Codeforces Round (Div. 2)", "Div. 2", start, 7200L, "u");
        when(upcoming.nextFor(eq(rating), any())).thenReturn(Optional.of(round));
        when(contests.findRecentContests(eq(USER), anyInt())).thenReturn(List.of(
            ContestSummary.builder().platform("CODEFORCES").problemsSolved(3).ratingChange(20).build(),
            ContestSummary.builder().platform("DOMJUDGE").problemsSolved(9).ratingChange(0).build(),
            ContestSummary.builder().platform("CODEFORCES").problemsSolved(1).ratingChange(-40).build()));

        engine.generateContestPrep(USER);

        Recommendation rec = saved("CONTEST_PREP");
        Plan.Summary summary = codec.readSummary(rec.getMetadata());
        assertEquals(rating, summary.rating());
        assertEquals(Plan.MEASURED, summary.ratingBasis());
        assertEquals(round, summary.contest());
        assertEquals(new Plan.ContestRecord(2, 2.0, -10.0), summary.record(), "Codeforces rounds only");
        assertEquals(start, rec.getExpiresAt());
    }

    @Test
    @DisplayName("without a rating, contest prep estimates one and looks for rounds open to the unrated")
    void contestPrepInferred() {
        when(accounts.findByUserUserIdAndPlatform(USER, "CODEFORCES")).thenReturn(Optional.empty());
        when(upcoming.nextFor(isNull(), any())).thenReturn(Optional.empty());
        when(contests.findRecentContests(eq(USER), anyInt())).thenReturn(List.of());

        engine.generateContestPrep(USER);

        Plan.Summary summary = codec.readSummary(saved("CONTEST_PREP").getMetadata());
        assertEquals(RecommendationEngine.DEFAULT_RATING, summary.rating());
        assertEquals(Plan.INFERRED, summary.ratingBasis());
        assertNull(summary.contest());
        assertNull(summary.record());
    }

    @Test
    @DisplayName("an unrated user's estimate is the 70th percentile of what they solved, to the hundred")
    void estimateRating() {
        assertEquals(800, RecommendationEngine.estimateRating(List.of()));
        assertEquals(800, RecommendationEngine.estimateRating(List.of(500)));
        assertEquals(1400, RecommendationEngine.estimateRating(
            List.of(1700, 800, 900, 1000, 1100, 1200, 1300, 1400, 1500, 1600)));
    }

    // -- revision ------------------------------------------------------------------------

    @Test
    @DisplayName("a newly faded skill is due at once; a scheduled one keeps its date")
    void revisionSchedule() {
        RoadmapTaxonomy.NodeDef other = RoadmapTaxonomy.NODES.get(RoadmapTaxonomy.NODES.indexOf(node) + 1);
        Instant later = Instant.now().plus(Duration.ofDays(6));
        RevisionSchedule scheduled = RevisionSchedule.builder().topic(other.id()).nextRevisionAt(later)
            .decayScore(12.0).intervalDays(6).easeFactor(2.4).build();
        when(revisions.findByUserUserId(USER)).thenReturn(List.of(scheduled));
        when(mastery.findDecayed(eq(USER), eq(TopicMastery.Scope.NODE), anyDouble())).thenReturn(List.of(
            TopicMastery.builder().topic(node.id()).masteryScore(60.0).decayScore(25.0).build(),
            TopicMastery.builder().topic(other.id()).masteryScore(80.0).decayScore(31.0).build()));

        Instant before = Instant.now();
        engine.generateRevisionSchedule(USER);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RevisionSchedule>> saved = ArgumentCaptor.forClass(List.class);
        verify(revisions).saveAll(saved.capture());
        RevisionSchedule fresh = saved.getValue().stream()
            .filter(r -> node.id().equals(r.getTopic())).findFirst().orElseThrow();
        assertFalse(fresh.getNextRevisionAt().isAfter(Instant.now()), "due now");
        assertFalse(fresh.getNextRevisionAt().isBefore(before));
        assertEquals(25, fresh.getRevisionPriority());

        assertEquals(later, scheduled.getNextRevisionAt(), "an existing date is not pushed back");
        assertEquals(31.0, scheduled.getDecayScore());
        assertEquals(31, scheduled.getRevisionPriority());
    }

    @Test
    @DisplayName("nothing faded writes nothing")
    void revisionNothingFaded() {
        when(mastery.findDecayed(eq(USER), any(), anyDouble())).thenReturn(List.of());
        engine.generateRevisionSchedule(USER);
        verify(revisions, never()).saveAll(any());
    }
}
