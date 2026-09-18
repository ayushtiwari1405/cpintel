package com.cpintel.compete;

import com.cpintel.integration.domjudge.DjModels;
import com.cpintel.integration.domjudge.DomjudgeClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The cache is the whole reason a 200-person DOMjudge round is affordable, so the properties
 * it is relied on for are tested rather than assumed.
 *
 * The failure this guards against is not subtle in effect but is invisible in code review: a
 * cache that refreshes on expiry turns 200 waiting contestants into 200 simultaneous requests
 * at the judge, in the same second, every few seconds. That is the exact load the arena moved
 * away from on the Codeforces side.
 */
class DomjudgeContestCacheTest {

    private static final String CID = "nwerc18";

    private DjModels.Submission submission(String id) {
        DjModels.Submission s = new DjModels.Submission();
        s.setId(id);
        s.setTeam_id("t1");
        return s;
    }

    @Test
    @DisplayName("a cold read by 200 threads at once costs the judge exactly one call")
    void coldReadIsSingleFlight() throws Exception {
        DomjudgeClient client = mock(DomjudgeClient.class);
        AtomicInteger calls = new AtomicInteger();

        when(client.getSubmissions(anyString())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            // A real judge takes a moment; without the pause every thread could serialise
            // naturally and the test would pass even against a broken implementation.
            Thread.sleep(50);
            return List.of(submission("1"));
        });

        DomjudgeContestCache cache = new DomjudgeContestCache(client);

        int readers = 200;
        CountDownLatch ready = new CountDownLatch(readers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger nonEmpty = new AtomicInteger();

        // One thread per reader: a smaller pool would queue most of the tasks, and they
        // would never reach the latch — the readers have to arrive genuinely together for
        // this to be testing anything.
        ExecutorService pool = Executors.newFixedThreadPool(readers);
        for (int i = 0; i < readers; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    if (!cache.submissions(CID).isEmpty()) nonEmpty.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "readers did not start");
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "readers did not finish");

        assertEquals(1, calls.get(),
            "200 contestants arriving together must cost one fetch, not 200");
        assertEquals(readers, nonEmpty.get(), "every reader should have got the data");
    }

    @Test
    @DisplayName("a warm read does not touch the judge at all")
    void warmReadIsFree() {
        DomjudgeClient client = mock(DomjudgeClient.class);
        when(client.getSubmissions(CID)).thenReturn(List.of(submission("1")));

        DomjudgeContestCache cache = new DomjudgeContestCache(client);

        for (int i = 0; i < 50; i++) cache.submissions(CID);

        verify(client, times(1)).getSubmissions(CID);
    }

    @Test
    @DisplayName("a judge that blinks after a good read keeps serving the last good copy")
    void staleSurvivesFailure() {
        DomjudgeClient client = mock(DomjudgeClient.class);
        when(client.getProblems(CID))
            .thenReturn(List.of(problem("A")))
            .thenThrow(new RuntimeException("judge unreachable"));

        // A zero-length TTL makes every read after the first one a refresh, which is the
        // moment this test is about.
        DomjudgeContestCache cache = new DomjudgeContestCache(
            client, Duration.ZERO, Duration.ZERO);

        assertEquals(1, cache.problems(CID).size());

        List<DjModels.ContestProblem> afterFailure = cache.problems(CID);
        assertEquals(1, afterFailure.size(),
            "a contest must not lose its problem list because the judge blinked");
    }

    @Test
    @DisplayName("a cold read against a dead judge surfaces the failure rather than lying")
    void coldFailurePropagates() {
        DomjudgeClient client = mock(DomjudgeClient.class);
        when(client.getProblems(CID)).thenThrow(new RuntimeException("judge unreachable"));

        DomjudgeContestCache cache = new DomjudgeContestCache(client);

        assertThrows(RuntimeException.class, () -> cache.problems(CID),
            "with nothing cached there is nothing honest to return");
    }

    @Test
    @DisplayName("team names match case- and whitespace-insensitively, on either name field")
    void teamLookupIsForgiving() {
        DomjudgeClient client = mock(DomjudgeClient.class);
        when(client.getTeams(CID)).thenReturn(List.of(
            team("7", "Team  Alpha", null),
            team("8", null, "Beta Squad")));

        DomjudgeContestCache cache = new DomjudgeContestCache(client);

        assertEquals("7", cache.teamIdByName(CID, "team alpha"));
        assertEquals("7", cache.teamIdByName(CID, "  Team   Alpha "));
        assertEquals("8", cache.teamIdByName(CID, "BETA SQUAD"));
        assertNull(cache.teamIdByName(CID, "Nobody"));
        assertNull(cache.teamIdByName(CID, null));
    }

    private DjModels.ContestProblem problem(String label) {
        DjModels.ContestProblem p = new DjModels.ContestProblem();
        p.setId("p-" + label);
        p.setLabel(label);
        p.setName("Problem " + label);
        return p;
    }

    private DjModels.Team team(String id, String name, String displayName) {
        DjModels.Team t = new DjModels.Team();
        t.setId(id);
        t.setName(name);
        t.setDisplay_name(displayName);
        return t;
    }
}
