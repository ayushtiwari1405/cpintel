package com.cpintel.service;

import com.cpintel.analytics.Plan;
import com.cpintel.analytics.PlanCodec;
import com.cpintel.analytics.RecommendationEngine;
import com.cpintel.common.RecomputeLock;
import com.cpintel.entity.Recommendation;
import com.cpintel.entity.RevisionSchedule;
import com.cpintel.entity.TopicMastery;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.RecommendationRepository;
import com.cpintel.repository.jpa.RevisionScheduleRepository;
import com.cpintel.repository.jpa.TopicMasteryRepository;
import com.cpintel.roadmap.RoadmapTaxonomy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * How the plans are served: generated on first view, read back with progress, regenerated only
 * past a cooldown, and the revision queue showing what is due - not what was due once.
 */
class RecommendationServiceTest {

    private static final long USER = 42L;

    private RecommendationRepository recommendations;
    private RevisionScheduleRepository revisions;
    private TopicMasteryRepository mastery;
    private RecommendationEngine engine;
    private RecomputeLock lock;
    private final PlanCodec codec = new PlanCodec(new ObjectMapper().findAndRegisterModules());
    private RecommendationService service;

    private final RoadmapTaxonomy.NodeDef nodeA = RoadmapTaxonomy.NODES.get(5);
    private final RoadmapTaxonomy.NodeDef nodeB = RoadmapTaxonomy.NODES.get(9);
    private final RoadmapTaxonomy.NodeDef nodeC = RoadmapTaxonomy.NODES.get(14);

    @BeforeEach
    void setUp() {
        recommendations = mock(RecommendationRepository.class);
        revisions = mock(RevisionScheduleRepository.class);
        mastery = mock(TopicMasteryRepository.class);
        engine = mock(RecommendationEngine.class);
        // No Redis: the lock is always granted, as it is when Redis is unreachable.
        lock = spy(new RecomputeLock(null));
        service = new RecommendationService(recommendations, revisions, mastery, engine, lock, codec);
    }

    private Recommendation row(String type, List<Plan.Item> items, Plan.Summary summary, Instant generatedAt) {
        return Recommendation.builder().recId(1L).recType(type)
            .problemList(codec.write(items)).metadata(summary == null ? null : codec.write(summary))
            .generatedAt(generatedAt).expiresAt(generatedAt.plus(Duration.ofDays(1))).build();
    }

    private static Plan.Problem problem(int contestId, boolean solved) {
        return new Plan.Problem(contestId, "A", "p" + contestId, 1200, solved, 80, Plan.CORE, "/practice", null);
    }

    private Plan.Item item(Plan.Problem... problems) {
        return new Plan.Item(nodeA.id(), nodeA.displayName(), nodeA.track(), nodeA.rollupTopic(), null,
            20L, 0L, 1200, "Weak area", "WEAK_TOPIC", null, Plan.MEASURED, null, List.of(problems));
    }

    // -- sheets ------------------------------------------------------------------

    @Test
    @DisplayName("first view generates the sheet, then serves it with progress counted")
    void generatesOnFirstView() {
        Recommendation stored = row("DAILY", List.of(item(problem(1, true), problem(2, false), problem(3, true))),
            Plan.Summary.of(Plan.MEASURED, List.of()), Instant.now());
        when(recommendations.findLatestActiveByUserAndType(USER, "DAILY"))
            .thenReturn(Optional.empty(), Optional.of(stored));

        var payload = service.getDaily(USER);

        verify(engine).generateDailySheet(USER);
        assertEquals("DAILY", payload.type());
        assertEquals(1, payload.items().size());
        assertEquals(new RecommendationService.Progress(2, 3), payload.progress());
        assertEquals(Plan.MEASURED, payload.summary().basis());
    }

    @Test
    @DisplayName("a live sheet is served without generating again")
    void liveSheetReused() {
        when(recommendations.findLatestActiveByUserAndType(USER, "WEEKLY"))
            .thenReturn(Optional.of(row("WEEKLY", List.of(item(problem(1, false))), null, Instant.now())));

        var payload = service.getWeekly(USER);

        verify(engine, never()).generateWeeklySheet(anyLong());
        assertNotNull(payload.summary(), "rows without a summary still get one");
    }

    @Test
    @DisplayName("sheets stored as plain maps before typed plans still read back")
    void legacyRowsRead() {
        String legacy = """
            [{"nodeKey":"%s","title":"T","track":"X","topic":"Y","blurb":"b","masteryScore":12,
              "decayScore":3,"targetRating":1100,"reason":"Weakest area","priority":"NEW",
              "problems":[{"contestId":5,"index":"B","name":"n","rating":1100,"solved":false,
                           "fit":71.2,"practicePath":"/practice?x","judgeUrl":"https://cf"}]}]
            """.formatted(nodeA.id());
        Recommendation stored = Recommendation.builder().recType("DAILY").problemList(legacy)
            .generatedAt(Instant.now()).build();
        when(recommendations.findLatestActiveByUserAndType(USER, "DAILY")).thenReturn(Optional.of(stored));

        var payload = service.getDaily(USER);

        assertEquals(1, payload.items().size());
        assertEquals(12L, payload.items().get(0).masteryScore());
        assertEquals("5B", payload.items().get(0).problems().get(0).key());
        assertEquals(new RecommendationService.Progress(0, 1), payload.progress());
    }

    @Test
    @DisplayName("an unreadable stored sheet reads as empty rather than failing the page")
    void unreadableRow() {
        Recommendation stored = Recommendation.builder().recType("DAILY").problemList("{not json")
            .metadata("[]").generatedAt(Instant.now()).build();
        when(recommendations.findLatestActiveByUserAndType(USER, "DAILY")).thenReturn(Optional.of(stored));

        var payload = service.getDaily(USER);
        assertTrue(payload.items().isEmpty());
        assertEquals(new RecommendationService.Progress(0, 0), payload.progress());
    }

    @Test
    @DisplayName("contest prep is generated through the engine like the others")
    void contestPrepGenerated() {
        when(recommendations.findLatestActiveByUserAndType(USER, "CONTEST_PREP"))
            .thenReturn(Optional.empty(), Optional.of(row("CONTEST_PREP", List.of(),
                new Plan.Summary(Plan.NONE, List.of("x"), null, 800, Plan.INFERRED, null, null), Instant.now())));

        var payload = service.getContestPrep(USER);
        verify(engine).generateContestPrep(USER);
        assertEquals(800, payload.summary().rating());
    }

    @Test
    @DisplayName("while another request holds the generation lock, the page is told to wait")
    void lockHeldElsewhere() {
        doReturn(null).when(lock).ifFree(anyString(), any(), any());
        when(recommendations.findLatestActiveByUserAndType(USER, "DAILY")).thenReturn(Optional.empty());

        var payload = service.getDaily(USER);

        verify(engine, never()).generateDailySheet(anyLong());
        assertTrue(payload.items().isEmpty());
        assertEquals(Plan.NONE, payload.summary().basis());
        assertTrue(payload.summary().notes().get(0).contains("being built"));
    }

    @Test
    @DisplayName("regenerate builds a new sheet once the cooldown has passed")
    void regenerateAfterCooldown() {
        Recommendation old = row("DAILY", List.of(item(problem(1, true))), null, Instant.now().minus(Duration.ofHours(2)));
        when(recommendations.findLatestActiveByUserAndType(USER, "DAILY")).thenReturn(Optional.of(old));

        service.regenerate(USER, RecommendationService.SheetType.DAILY);
        verify(engine).generateDailySheet(USER);
    }

    @Test
    @DisplayName("regenerate within the cooldown returns the sheet just built")
    void regenerateThrottled() {
        Recommendation fresh = row("DAILY", List.of(item(problem(1, false))), null, Instant.now().minusSeconds(5));
        when(recommendations.findLatestActiveByUserAndType(USER, "DAILY")).thenReturn(Optional.of(fresh));

        var payload = service.regenerate(USER, RecommendationService.SheetType.DAILY);
        verify(engine, never()).generateDailySheet(anyLong());
        assertEquals(1, payload.items().size());
    }

    @Test
    @DisplayName("plan types in the URL are daily, weekly and contest-prep only")
    void sheetTypes() {
        assertEquals(RecommendationService.SheetType.CONTEST_PREP,
            RecommendationService.SheetType.fromPath("contest-prep"));
        assertEquals(RecommendationService.SheetType.WEEKLY, RecommendationService.SheetType.fromPath("Weekly"));
        for (String bad : new String[] {null, "", "revision", "DAILY; drop"}) {
            ApiException e = assertThrows(ApiException.class, () -> RecommendationService.SheetType.fromPath(bad));
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        }
    }

    // -- revision ------------------------------------------------------------------

    private RevisionSchedule schedule(long id, RoadmapTaxonomy.NodeDef def, Instant next) {
        return RevisionSchedule.builder().revisionId(id).topic(def.id()).nextRevisionAt(next)
            .decayScore(30.0).revisionPriority(30).intervalDays(4).repetitionCount(1).easeFactor(2.2).build();
    }

    private static TopicMastery mastery(RoadmapTaxonomy.NodeDef def, double decay) {
        return TopicMastery.builder().topic(def.id()).scope(TopicMastery.Scope.NODE)
            .masteryScore(70.0).decayScore(decay).lastPracticedAt(Instant.now().minus(Duration.ofDays(50))).build();
    }

    @Test
    @DisplayName("due items are past their date and still faded; practised ones drop off; upcoming follow")
    void revisionQueue() {
        Instant now = Instant.now();
        when(revisions.findByUserUserId(USER)).thenReturn(List.of(
            schedule(1, nodeA, now.minus(Duration.ofDays(2))),     // due, faded 40
            schedule(2, nodeB, now.minus(Duration.ofDays(1))),     // past date but practised since
            schedule(3, nodeC, now.plus(Duration.ofDays(5)))));    // upcoming
        when(mastery.findByUserUserIdAndScope(USER, TopicMastery.Scope.NODE)).thenReturn(List.of(
            mastery(nodeA, 40), mastery(nodeB, 2), mastery(nodeC, 15)));
        Plan.Problem resolve = new Plan.Problem(9, "C", "old", 1000, true, 50, Plan.RESOLVE, "/p", null);
        when(engine.revisionProblems(USER, List.of(nodeA.id()))).thenReturn(Map.of(nodeA.id(), List.of(resolve)));

        var queue = service.getRevisionQueue(USER, 14);

        assertEquals(List.of(nodeA.id(), nodeC.id()), queue.stream().map(RecommendationService.RevisionItem::nodeKey).toList());
        assertEquals("DUE", queue.get(0).status());
        assertEquals(List.of(resolve), queue.get(0).problems());
        assertTrue(queue.get(0).reason().contains("40%"));
        assertEquals("UPCOMING", queue.get(1).status());
        assertTrue(queue.get(1).problems().isEmpty());
        verify(engine, never()).generateRevisionSchedule(anyLong());
    }

    @Test
    @DisplayName("the horizon decides how far ahead upcoming items are shown")
    void revisionHorizon() {
        Instant now = Instant.now();
        when(revisions.findByUserUserId(USER)).thenReturn(List.of(schedule(3, nodeC, now.plus(Duration.ofDays(5)))));
        when(mastery.findByUserUserIdAndScope(USER, TopicMastery.Scope.NODE)).thenReturn(List.of());
        when(engine.revisionProblems(eq(USER), anyList())).thenReturn(Map.of());

        assertTrue(service.getRevisionQueue(USER, 0).isEmpty());
        assertEquals(1, service.getRevisionQueue(USER, 7).size());
    }

    @Test
    @DisplayName("an empty schedule is built once on first view")
    void revisionScheduleBuiltWhenEmpty() {
        when(revisions.findByUserUserId(USER)).thenReturn(List.of());
        when(mastery.findByUserUserIdAndScope(USER, TopicMastery.Scope.NODE)).thenReturn(List.of());
        when(engine.revisionProblems(eq(USER), anyList())).thenReturn(Map.of());

        assertTrue(service.getRevisionQueue(USER, 14).isEmpty());
        verify(engine).generateRevisionSchedule(USER);
    }

    @Test
    @DisplayName("a horizon outside 0-60 days is a 400")
    void revisionHorizonValidated() {
        for (int bad : new int[] {-1, RecommendationService.MAX_HORIZON_DAYS + 1}) {
            ApiException e = assertThrows(ApiException.class, () -> service.getRevisionQueue(USER, bad));
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        }
        verifyNoInteractions(revisions);
    }

    @Test
    @DisplayName("somebody else's revision item is not found, and nothing is written")
    void markDoneOthersItem() {
        when(revisions.findByRevisionIdAndUserUserId(5L, USER)).thenReturn(Optional.empty());
        ApiException e = assertThrows(ApiException.class, () -> service.markRevisionDone(USER, 5L));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        verify(revisions, never()).save(any());
    }

    @Test
    @DisplayName("marking done pushes the next revision out by the grown interval")
    void markDoneSchedulesNext() {
        RevisionSchedule rs = schedule(5, nodeA, Instant.now().minus(Duration.ofDays(1)));
        when(revisions.findByRevisionIdAndUserUserId(5L, USER)).thenReturn(Optional.of(rs));

        service.markRevisionDone(USER, 5L);

        assertEquals(2, rs.getRepetitionCount());
        assertEquals(2.3, rs.getEaseFactor(), 1e-9);
        assertEquals(9, rs.getIntervalDays());
        assertTrue(rs.getNextRevisionAt().isAfter(Instant.now().plus(Duration.ofDays(8))));
        verify(revisions).save(rs);
    }
}
