package com.cpintel.compete;

import com.cpintel.integration.domjudge.DjModels;
import com.cpintel.integration.domjudge.DomjudgeClient;
import com.cpintel.integration.domjudge.DomjudgeCredentialStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
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

        when(client.getSubmissions(isNull(), anyString())).thenAnswer(invocation -> {
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
                    if (!cache.submissions(null, CID).isEmpty()) nonEmpty.incrementAndGet();
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
        when(client.getSubmissions(null, CID)).thenReturn(List.of(submission("1")));

        DomjudgeContestCache cache = new DomjudgeContestCache(client);

        for (int i = 0; i < 50; i++) cache.submissions(null, CID);

        verify(client, times(1)).getSubmissions(null, CID);
    }

    @Test
    @DisplayName("a judge that blinks after a good read keeps serving the last good copy")
    void staleSurvivesFailure() {
        DomjudgeClient client = mock(DomjudgeClient.class);
        when(client.getProblems(null, CID))
            .thenReturn(List.of(problem("A")))
            .thenThrow(new RuntimeException("judge unreachable"));

        // A zero-length TTL makes every read after the first one a refresh, which is the
        // moment this test is about.
        DomjudgeContestCache cache = new DomjudgeContestCache(
            client, Duration.ZERO, Duration.ZERO);

        assertEquals(1, cache.problems(null, CID).size());

        List<DjModels.ContestProblem> afterFailure = cache.problems(null, CID);
        assertEquals(1, afterFailure.size(),
            "a contest must not lose its problem list because the judge blinked");
    }

    @Test
    @DisplayName("a cold read against a dead judge surfaces the failure rather than lying")
    void coldFailurePropagates() {
        DomjudgeClient client = mock(DomjudgeClient.class);
        when(client.getProblems(null, CID)).thenThrow(new RuntimeException("judge unreachable"));

        DomjudgeContestCache cache = new DomjudgeContestCache(client);

        assertThrows(RuntimeException.class, () -> cache.problems(null, CID),
            "with nothing cached there is nothing honest to return");
    }

    @Test
    @DisplayName("team names match case- and whitespace-insensitively, on either name field")
    void teamLookupIsForgiving() {
        DomjudgeClient client = mock(DomjudgeClient.class);
        when(client.getTeams(null, CID)).thenReturn(List.of(
            team("7", "Team  Alpha", null),
            team("8", null, "Beta Squad")));

        DomjudgeContestCache cache = new DomjudgeContestCache(client);

        assertEquals("7", cache.teamIdByName(null, CID, "team alpha"));
        assertEquals("7", cache.teamIdByName(null, CID, "  Team   Alpha "));
        assertEquals("8", cache.teamIdByName(null, CID, "BETA SQUAD"));
        assertNull(cache.teamIdByName(null, CID, "Nobody"));
        assertNull(cache.teamIdByName(null, CID, null));
    }

    @Test
    @DisplayName("two contestants never share a cached entry, so neither sees the other's rows")
    void entriesAreScopedToTheCredentialsThatFilledThem() {
        DomjudgeClient client = mock(DomjudgeClient.class);

        DomjudgeCredentialStore.Stored alice = new DomjudgeCredentialStore.Stored(
            "alice", "pw", "Alice", "t1", "Team One", null, null, Instant.EPOCH);
        DomjudgeCredentialStore.Stored bob = new DomjudgeCredentialStore.Stored(
            "bob", "pw", "Bob", "t2", "Team Two", null, null, Instant.EPOCH);

        // DOMjudge filters a team account's view of the submissions list to its own team, so
        // these two genuinely get different answers to the same question.
        when(client.getSubmissions(alice, CID)).thenReturn(List.of(submission("alice-1")));
        when(client.getSubmissions(bob, CID)).thenReturn(List.of(submission("bob-1")));

        DomjudgeContestCache cache = new DomjudgeContestCache(client);

        assertEquals("alice-1", cache.submissions(alice, CID).get(0).getId());
        assertEquals("bob-1", cache.submissions(bob, CID).get(0).getId(),
            "bob must not be served the entry alice's credentials filled");

        // And each is still cached in its own right, rather than evicting the other.
        assertEquals("alice-1", cache.submissions(alice, CID).get(0).getId());
        verify(client, times(1)).getSubmissions(alice, CID);
        verify(client, times(1)).getSubmissions(bob, CID);
    }

    @Test
    @DisplayName("evicting a contest clears it for every identity that cached it")
    void evictIsContestWide() {
        DomjudgeClient client = mock(DomjudgeClient.class);
        DomjudgeCredentialStore.Stored alice = new DomjudgeCredentialStore.Stored(
            "alice", "pw", "Alice", "t1", "Team One", null, null, Instant.EPOCH);

        when(client.getSubmissions(alice, CID)).thenReturn(List.of(submission("1")));
        when(client.getSubmissions(null, CID)).thenReturn(List.of(submission("1")));

        DomjudgeContestCache cache = new DomjudgeContestCache(client);
        cache.submissions(alice, CID);
        cache.submissions(null, CID);

        cache.evict(CID);

        // Leaving one identity's copy in place would make a submission appear for its author
        // several seconds before anybody else.
        cache.submissions(alice, CID);
        cache.submissions(null, CID);
        verify(client, times(2)).getSubmissions(alice, CID);
        verify(client, times(2)).getSubmissions(null, CID);
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
