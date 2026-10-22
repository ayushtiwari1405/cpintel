package com.cpintel.integration.codeforces;

import com.cpintel.analytics.Plan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Which round a user is pointed at, and what happens when Codeforces does not answer. */
class CfUpcomingContestsTest {

    private static final Instant NOW = Instant.parse("2026-10-22T09:00:00Z");

    private static CfStandingsResponse.Contest contest(int id, String name, String phase, Duration fromNow) {
        CfStandingsResponse.Contest c = new CfStandingsResponse.Contest();
        c.setId(id);
        c.setName(name);
        c.setPhase(phase);
        c.setStartTimeSeconds(NOW.plus(fromNow).getEpochSecond());
        c.setDurationSeconds(7200L);
        return c;
    }

    @Test
    @DisplayName("divisions are read from the round's name")
    void divisions() {
        assertEquals("Div. 2", CfUpcomingContests.division("Codeforces Round 1000 (Div. 2)"));
        assertEquals("Div. 1 + 2", CfUpcomingContests.division("Codeforces Round 999 (Div. 1 + Div. 2)"));
        assertEquals("Div. 1 + 2", CfUpcomingContests.division("Codeforces Global Round 30"));
        assertEquals("Educational", CfUpcomingContests.division("Educational Codeforces Round 180 (Rated for Div. 2)"));
        assertEquals("Div. 1", CfUpcomingContests.division("Codeforces Round 1001 (Div. 1)"));
        assertEquals("Div. 4", CfUpcomingContests.division("Codeforces Round 1002 (Div. 4)"));
        assertNull(CfUpcomingContests.division("Kotlin Heroes: Episode 13"));
        assertNull(CfUpcomingContests.division(null));
    }

    @Test
    @DisplayName("each division admits the ratings it is rated for; the unrated may enter all but Div. 1")
    void eligibility() {
        assertTrue(CfUpcomingContests.admits("Div. 4", 1399));
        assertFalse(CfUpcomingContests.admits("Div. 4", 1400));
        assertTrue(CfUpcomingContests.admits("Div. 3", 1599));
        assertFalse(CfUpcomingContests.admits("Div. 3", 1600));
        assertTrue(CfUpcomingContests.admits("Div. 2", 2099));
        assertFalse(CfUpcomingContests.admits("Educational", 2100));
        assertTrue(CfUpcomingContests.admits("Div. 1", 1900));
        assertFalse(CfUpcomingContests.admits("Div. 1", null));
        assertTrue(CfUpcomingContests.admits("Div. 2", null));
        assertTrue(CfUpcomingContests.admits("Div. 1 + 2", 3000));
        assertFalse(CfUpcomingContests.admits(null, 1500));
    }

    @Test
    @DisplayName("the soonest eligible round within two weeks is chosen")
    void soonestEligible() {
        CodeforcesClient client = mock(CodeforcesClient.class);
        when(client.getContests()).thenReturn(List.of(
            contest(1, "Codeforces Round 1 (Div. 1)", "BEFORE", Duration.ofHours(3)),
            contest(2, "Codeforces Round 2 (Div. 3)", "BEFORE", Duration.ofHours(30)),
            contest(3, "Codeforces Round 3 (Div. 2)", "BEFORE", Duration.ofHours(20)),
            contest(4, "Codeforces Round 4 (Div. 2)", "FINISHED", Duration.ofHours(-50)),
            contest(5, "Codeforces Round 5 (Div. 2)", "BEFORE", Duration.ofDays(20)),
            contest(6, "Kotlin Heroes", "BEFORE", Duration.ofHours(1))));
        CfUpcomingContests upcoming = new CfUpcomingContests(client);

        Optional<Plan.Contest> forPupil = upcoming.nextFor(1300, NOW);
        assertEquals(3, forPupil.orElseThrow().id());
        assertEquals("Div. 2", forPupil.get().division());
        assertEquals(NOW.plus(Duration.ofHours(20)), forPupil.get().startsAt());
        assertEquals("https://codeforces.com/contests/3", forPupil.get().url());

        assertEquals(1, upcoming.nextFor(2200, NOW).orElseThrow().id());
        verify(client, times(1)).getContests();
    }

    @Test
    @DisplayName("a failed fetch gives no round and is not retried on the very next request")
    void failureBacksOff() {
        CodeforcesClient client = mock(CodeforcesClient.class);
        when(client.getContests()).thenThrow(new IllegalStateException("down"));
        CfUpcomingContests upcoming = new CfUpcomingContests(client);

        assertTrue(upcoming.nextFor(1500, NOW).isEmpty());
        assertTrue(upcoming.nextFor(1500, NOW.plusSeconds(60)).isEmpty());
        verify(client, times(1)).getContests();

        upcoming.nextFor(1500, NOW.plus(Duration.ofMinutes(10)));
        verify(client, times(2)).getContests();
    }
}
