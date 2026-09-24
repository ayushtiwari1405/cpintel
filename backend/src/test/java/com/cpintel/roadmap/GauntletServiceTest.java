package com.cpintel.roadmap;

import com.cpintel.entity.PlacementResult;
import com.cpintel.entity.RoadmapNode;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.PlacementResultRepository;
import com.cpintel.repository.jpa.RoadmapNodeRepository;
import com.cpintel.service.RoadmapService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** How the gauntlet turns answers into a placement, and a placement into the roadmap. */
class GauntletServiceTest {

    private static final Long USER = 7L;

    private RoadmapNodeRepository nodes;
    private PlacementResultRepository results;
    private GauntletService service;
    private List<RoadmapNode> tree;

    /** Attempts in a map, standing in for Redis. */
    private static class MemoryAttempts implements GauntletAttempts {
        private final Map<String, Attempt> store = new HashMap<>();
        @Override public void save(String id, Attempt a) { store.put(id, a); }
        @Override public Optional<Attempt> find(String id) { return Optional.ofNullable(store.get(id)); }
        @Override public void delete(String id) { store.remove(id); }
    }

    @BeforeEach
    void setUp() {
        nodes = mock(RoadmapNodeRepository.class);
        results = mock(PlacementResultRepository.class);
        when(results.save(any())).thenAnswer(call -> call.getArgument(0));

        tree = RoadmapTaxonomy.NODES.stream()
            .map(def -> RoadmapNode.builder().nodeKey(def.id())
                .status(def.prereqIds().isEmpty() ? "UNLOCKED" : "LOCKED").build())
            .collect(Collectors.toList());
        when(nodes.findByUserUserIdOrderByOrderIndex(USER)).thenReturn(tree);

        service = new GauntletService(mock(RoadmapService.class), nodes, results,
            new MemoryAttempts(), new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    private static List<GauntletBank.Question> tier(String section, int tier) {
        return GauntletBank.QUESTIONS.stream()
            .filter(q -> q.section().equals(section) && q.tier() == tier).toList();
    }

    /**
     * Climbs every area the way the page does: tier by tier, right answers up to the level
     * given for that area, then a miss that ends the climb. Returns the attempt id.
     */
    private String climb(Map<String, Integer> levels) {
        String attempt = service.start(USER).attemptId();
        for (GauntletBank.Section section : GauntletBank.SECTIONS) {
            int level = levels.getOrDefault(section.id(), 0);
            for (int t = 1; t <= GauntletBank.TIER_RATING.length; t++) {
                Map<String, Integer> picks = new HashMap<>();
                for (GauntletBank.Question q : tier(section.id(), t)) {
                    picks.put(q.id(), t <= level ? GauntletService.correctIndex(q) : -1);
                }
                service.check(USER, attempt, picks);
                if (t > level) break;
            }
        }
        return attempt;
    }

    private String status(String key) {
        return tree.stream().filter(n -> key.equals(n.getNodeKey())).findFirst()
            .orElseThrow().getStatus();
    }

    @Test
    @DisplayName("every question has four options, and serving never marks the right one")
    void servedQuestionsAreUnmarked() {
        GauntletDto.Paper paper = service.paper(USER);
        assertEquals(GauntletBank.SECTIONS.size(), paper.sections().size());
        paper.sections().forEach(s -> {
            assertEquals(8, s.questions().size(), s.id());
            s.questions().forEach(q -> assertEquals(4, q.options().size(), q.id()));
        });
        // The correct option is written first in the bank; a fixed shuffle must not leave it
        // first everywhere, or the answer key is simply "pick A".
        long firstIsRight = GauntletBank.QUESTIONS.stream()
            .filter(q -> GauntletService.correctIndex(q) == 0).count();
        assertTrue(firstIsRight < GauntletBank.QUESTIONS.size() / 2);
    }

    @Test
    @DisplayName("a perfect run places every area at the top tier and completes low nodes")
    void perfectRun() {
        Map<String, Integer> all = new HashMap<>();
        GauntletBank.SECTIONS.forEach(sec -> all.put(sec.id(), 4));

        GauntletDto.ResultView result = service.submit(USER, climb(all));

        assertEquals(2400, result.overallRating());
        result.sections().forEach(s -> assertEquals(4, s.tiersPassed(), s.section()));
        assertEquals("COMPLETED", status("io-basics"));
        assertEquals("COMPLETED", status("dijkstra"));
        assertTrue(result.nodesPlaced() > 0);
    }

    @Test
    @DisplayName("the climb stops at the first missed tier, and places the roadmap there")
    void climbStopsAtFirstMissedTier() {
        GauntletDto.ResultView result = service.submit(USER, climb(Map.of("graphs", 2)));

        GauntletDto.SectionResult graphs = result.sections().stream()
            .filter(s -> s.section().equals("graphs")).findFirst().orElseThrow();
        assertEquals(2, graphs.tiersPassed());
        assertEquals(1600, graphs.rating());
        assertEquals("COMPLETED", status("bfs"));          // 1200-1500
        assertEquals("UNLOCKED", status("dijkstra"));      // 1600-2000, within the frontier
        assertEquals("LOCKED", status("scc"));             // 2100-2500, beyond it
    }

    @Test
    @DisplayName("a higher tier cannot be checked before the one below it is passed")
    void cannotSkipAhead() {
        String attempt = service.start(USER).attemptId();
        Map<String, Integer> tierFour = new HashMap<>();
        tier("graphs", 4).forEach(q -> tierFour.put(q.id(), 0));

        assertThrows(ApiException.class, () -> service.check(USER, attempt, tierFour));
    }

    @Test
    @DisplayName("the first checked answer is final — the check route is not an answer key")
    void answersLockOnFirstCheck() {
        String attempt = service.start(USER).attemptId();
        GauntletBank.Question q = tier("math", 1).get(0);

        service.check(USER, attempt, Map.of(q.id(), -1));

        assertThrows(ApiException.class, () ->
            service.check(USER, attempt, Map.of(q.id(), GauntletService.correctIndex(q))));
    }

    @Test
    @DisplayName("somebody else's attempt cannot be checked or submitted")
    void attemptsBelongToTheirUser() {
        String attempt = service.start(USER).attemptId();

        assertThrows(ApiException.class, () -> service.submit(99L, attempt));
    }

    @Test
    @DisplayName("placement never undoes a completion")
    void neverMovesBackwards() {
        tree.stream().filter(n -> n.getNodeKey().equals("suffix-array")).findFirst()
            .orElseThrow().setStatus("COMPLETED");

        service.submit(USER, climb(Map.of()));

        assertEquals("COMPLETED", status("suffix-array"));
    }

    @Test
    @DisplayName("a retake waits a week — the answers were shown last time")
    void retakeWaits() {
        when(results.findFirstByUserIdOrderByCreatedAtDesc(USER)).thenReturn(Optional.of(
            com.cpintel.entity.PlacementResult.builder()
                .overallRating(1600).sections("[]").nodesPlaced(0)
                .createdAt(java.time.Instant.now().minus(java.time.Duration.ofDays(2)))
                .build()));

        assertThrows(ApiException.class, () -> service.start(USER));
        assertNotNull(service.paper(USER).nextAttemptAt());
    }
}
