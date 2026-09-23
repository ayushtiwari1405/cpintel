package com.cpintel.events;

import com.cpintel.entity.GroupContest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where an event is in its own life.
 *
 * <p>Worth pinning down precisely because the rule is a split one: two of the five states are
 * stored and three are read from the clock. Get that wrong in either direction and the failure
 * is silent and bad — a half-written examination that appears on two hundred candidates'
 * screens because its start time passed, or a live paper that refuses submissions because
 * nobody remembered to press a button.
 */
class EventLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    private GroupContest event(String lifecycle, Instant startsAt, Instant endsAt) {
        return GroupContest.builder()
            .kind(GroupContest.Kind.EXAM.name())
            .lifecycle(lifecycle)
            .platform("DOMJUDGE")
            .externalId("midsem")
            .name("Mid-semester practical")
            .startsAt(startsAt)
            .endsAt(endsAt)
            .build();
    }

    @Nested
    @DisplayName("The clock decides the middle three")
    class FromTheWindow {

        @Test
        @DisplayName("before its window, a published event is scheduled")
        void beforeTheWindow() {
            GroupContest exam = event("SCHEDULED",
                NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(3)));

            assertEquals(GroupContest.Lifecycle.SCHEDULED, exam.effectiveLifecycle(NOW));
            assertFalse(exam.isOpenForParticipation(NOW));
        }

        @Test
        @DisplayName("inside its window it is active, without anybody pressing anything")
        void insideTheWindow() {
            GroupContest exam = event("SCHEDULED",
                NOW.minus(Duration.ofMinutes(10)), NOW.plus(Duration.ofHours(2)));

            assertEquals(GroupContest.Lifecycle.ACTIVE, exam.effectiveLifecycle(NOW));
            assertTrue(exam.isOpenForParticipation(NOW));
        }

        @Test
        @DisplayName("past its window it is ended, even if it is still marked scheduled")
        void pastTheWindow() {
            // This is the case a stored status would get wrong: the row says SCHEDULED because
            // nothing has written to it since it was published, and the clock says otherwise.
            GroupContest exam = event("SCHEDULED",
                NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofHours(1)));

            assertEquals(GroupContest.Lifecycle.ENDED, exam.effectiveLifecycle(NOW));
            assertFalse(exam.isOpenForParticipation(NOW));
        }

        @Test
        @DisplayName("an event with no window yet is scheduled, not live")
        void noWindow() {
            GroupContest exam = event("SCHEDULED", null, null);

            assertEquals(GroupContest.Lifecycle.SCHEDULED, exam.effectiveLifecycle(NOW));
            assertFalse(exam.isOpenForParticipation(NOW));
        }
    }

    @Nested
    @DisplayName("The clock cannot override the two stored states")
    class Stored {

        @Test
        @DisplayName("a draft inside its own window stays shut")
        void draftStaysShut() {
            // The whole point of DRAFT. An examination saved with today's date on it must not
            // open itself while it is still being written.
            GroupContest exam = event("DRAFT",
                NOW.minus(Duration.ofMinutes(10)), NOW.plus(Duration.ofHours(2)));

            assertEquals(GroupContest.Lifecycle.DRAFT, exam.effectiveLifecycle(NOW));
            assertFalse(exam.isOpenForParticipation(NOW));
        }

        @Test
        @DisplayName("an archived event does not reopen because its window is re-entered")
        void archivedStaysArchived() {
            GroupContest exam = event("ARCHIVED",
                NOW.minus(Duration.ofMinutes(10)), NOW.plus(Duration.ofHours(2)));

            assertEquals(GroupContest.Lifecycle.ARCHIVED, exam.effectiveLifecycle(NOW));
            assertFalse(exam.isOpenForParticipation(NOW));
        }

        @Test
        @DisplayName("an unreadable lifecycle falls back to scheduled rather than to live")
        void unreadableFallsBackClosed() {
            // A value from a newer build, or a hand-edited row. Whatever it meant, the safe
            // reading is "not open": an event that fails closed is recoverable, one that
            // fails open has already happened to somebody.
            GroupContest exam = event("WHATEVER",
                NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(2)));

            assertEquals(GroupContest.Lifecycle.SCHEDULED, exam.effectiveLifecycle(NOW));
            assertFalse(exam.isOpenForParticipation(NOW));
        }
    }

    @Test
    @DisplayName("isExam is what separates the two products sharing this row")
    void kindSeparatesTheProducts() {
        GroupContest exam = event("SCHEDULED", NOW, NOW.plus(Duration.ofHours(1)));
        assertTrue(exam.isExam());

        GroupContest contest = GroupContest.builder()
            .kind(GroupContest.Kind.CONTEST.name())
            .lifecycle("SCHEDULED")
            .platform("CODEFORCES")
            .externalId("1900")
            .name("Div 2")
            .build();
        assertFalse(contest.isExam());
    }
}
