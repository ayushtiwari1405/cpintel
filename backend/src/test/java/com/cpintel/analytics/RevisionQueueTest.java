package com.cpintel.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The daily revision allowance, the order of the queue behind it, and that nothing starves. */
class RevisionQueueTest {

    private static final Instant NOW = Instant.parse("2026-10-22T09:00:00Z");

    private static RevisionQueue.Candidate c(String key, double decay, int daysOverdue) {
        return new RevisionQueue.Candidate(key, decay, NOW.minus(Duration.ofDays(daysOverdue)));
    }

    private static List<RevisionQueue.Status> statuses(List<RevisionQueue.Placed> placed) {
        return placed.stream().map(RevisionQueue.Placed::status).toList();
    }

    @Test
    @DisplayName("a few overdue skills are all due")
    void fewAreAllDue() {
        var placed = RevisionQueue.arrange(List.of(c("a", 20, 0), c("b", 30, 0)), NOW, RevisionQueue.openSlots(0));
        assertEquals(List.of(RevisionQueue.Status.DUE, RevisionQueue.Status.DUE), statuses(placed));
        assertEquals("b", placed.get(0).candidate().key(), "most faded first");
    }

    @Test
    @DisplayName("a large backlog offers the daily allowance and lists every other skill as queued")
    void backlogIsBounded() {
        List<RevisionQueue.Candidate> overdue = new ArrayList<>();
        for (int i = 0; i < 21; i++) overdue.add(c("s" + i, 10 + i * 2, 0));

        var placed = RevisionQueue.arrange(overdue, NOW, RevisionQueue.openSlots(0));

        assertEquals(21, placed.size(), "nothing overdue is dropped");
        assertEquals(RevisionQueue.SLOTS_PER_DAY,
            placed.stream().filter(p -> p.status() == RevisionQueue.Status.DUE).count());
        assertEquals(List.of("s20", "s19", "s18", "s17"),
            placed.subList(0, 4).stream().map(p -> p.candidate().key()).toList());
        for (int i = 0; i < placed.size(); i++) assertEquals(i + 1, placed.get(i).position());
    }

    @Test
    @DisplayName("revisions completed today use up today's allowance")
    void completedTodayCounts() {
        List<RevisionQueue.Candidate> overdue = List.of(c("a", 50, 0), c("b", 40, 0), c("c", 30, 0));
        assertEquals(2, RevisionQueue.arrange(overdue, NOW, RevisionQueue.openSlots(2)).stream()
            .filter(p -> p.status() == RevisionQueue.Status.DUE).count());
        assertTrue(RevisionQueue.arrange(overdue, NOW, RevisionQueue.openSlots(4)).stream()
            .allMatch(p -> p.status() == RevisionQueue.Status.QUEUED));
        assertEquals(0, RevisionQueue.openSlots(9));
        assertEquals(RevisionQueue.SLOTS_PER_DAY, RevisionQueue.openSlots(-1));
    }

    @Test
    @DisplayName("waiting raises priority, so an old mildly faded skill overtakes a fresh badly faded one")
    void agingOvertakes() {
        var placed = RevisionQueue.arrange(List.of(c("fresh", 55, 0), c("old", 12, 10)), NOW, 1);
        assertEquals("old", placed.get(0).candidate().key());
        assertEquals(12 + 10 * RevisionQueue.AGE_WEIGHT, placed.get(0).priority());
    }

    @Test
    @DisplayName("the same overdue skills give the same queue in any order")
    void deterministic() {
        List<RevisionQueue.Candidate> overdue = new ArrayList<>();
        for (int i = 0; i < 15; i++) overdue.add(c("s" + i, 10 + (i % 4) * 5, i % 3));
        List<RevisionQueue.Candidate> shuffled = new ArrayList<>(overdue);
        Collections.shuffle(shuffled, new Random(7));
        assertEquals(RevisionQueue.arrange(overdue, NOW, 4), RevisionQueue.arrange(shuffled, NOW, 4));
    }

    @Test
    @DisplayName("the day boundary is UTC midnight")
    void startOfDay() {
        assertEquals(Instant.parse("2026-10-22T00:00:00Z"), RevisionQueue.startOfDay(NOW));
    }

    /**
     * A month of a user who does exactly the day's revisions, while new skills fade at the rate
     * the allowance clears them - the case where ordering by decay alone would leave a mildly
     * faded skill behind every newcomer for ever.
     */
    @Test
    @DisplayName("under steady new fades, every waiting skill is reached within a bounded wait")
    void nothingStarves() {
        Map<String, RevisionQueue.Candidate> waiting = new HashMap<>();
        Map<String, Integer> arrivedOn = new HashMap<>();
        // A backlog after a long break: 20 skills, mostly mildly faded.
        for (int i = 0; i < 20; i++) {
            waiting.put("old" + i, new RevisionQueue.Candidate("old" + i, 11 + i, NOW));
            arrivedOn.put("old" + i, 0);
        }
        Random rnd = new Random(42);
        int longestWait = 0;
        for (int day = 0; day < 30; day++) {
            Instant today = NOW.plus(Duration.ofDays(day));
            // As many new fades a day as the allowance clears, all of them badly faded.
            for (int k = 0; k < RevisionQueue.SLOTS_PER_DAY; k++) {
                String key = "d" + day + "-" + k;
                waiting.put(key, new RevisionQueue.Candidate(key, 50 + rnd.nextInt(30), today));
                arrivedOn.put(key, day);
            }
            for (RevisionQueue.Placed p : RevisionQueue.arrange(new ArrayList<>(waiting.values()), today,
                    RevisionQueue.openSlots(0))) {
                if (p.status() != RevisionQueue.Status.DUE) continue;
                String key = p.candidate().key();
                longestWait = Math.max(longestWait, day - arrivedOn.get(key));
                waiting.remove(key);
            }
        }
        // Everything from the original backlog has been reached...
        assertTrue(waiting.keySet().stream().noneMatch(k -> k.startsWith("old")), "starved: " + waiting.keySet());
        // ...and nothing waited longer than the age term needs to lift the mildest decay (11)
        // past the worst newcomer (79), plus the backlog's own share of days.
        int bound = (int) Math.ceil((79 - 11) / RevisionQueue.AGE_WEIGHT) + 20 / RevisionQueue.SLOTS_PER_DAY + 1;
        assertTrue(longestWait <= bound, "longest wait " + longestWait + " days, bound " + bound);
    }
}
