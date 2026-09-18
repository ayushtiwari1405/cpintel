package com.cpintel.integration.domjudge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two DOMjudge time formats.
 *
 * Worth testing directly because both failure modes are silent: a duration that parses to the
 * wrong number gives a countdown that is confidently hours out, and a timestamp that fails to
 * parse reads as "the contest has not started". Neither throws, and neither is obvious from
 * looking at the page during a round.
 */
class DjTimeTest {

    @Nested
    @DisplayName("durations, written H:MM:SS.mmm")
    class Durations {

        @Test
        @DisplayName("a five-hour contest")
        void fiveHours() {
            assertEquals(Duration.ofHours(5), DjTime.duration("5:00:00.000"));
        }

        @Test
        @DisplayName("hours are not zero-padded and are not bounded by 24")
        void longContest() {
            assertEquals(Duration.ofHours(31), DjTime.duration("31:00:00.000"));
        }

        @Test
        @DisplayName("milliseconds are optional")
        void withoutMillis() {
            assertEquals(Duration.ofMinutes(90), DjTime.duration("1:30:00"));
        }

        @Test
        @DisplayName("milliseconds are read, not discarded")
        void withMillis() {
            assertEquals(Duration.ofMillis(1_500), DjTime.duration("0:00:01.500"));
        }

        @Test
        @DisplayName("contest_time before the start is negative")
        void negative() {
            assertEquals(Duration.ofMinutes(-15), DjTime.duration("-0:15:00.000"));
        }

        @Test
        @DisplayName("anything unrecognised is null rather than an exception")
        void garbage() {
            assertNull(DjTime.duration("not a duration"));
            assertNull(DjTime.duration("5:00"));
            assertNull(DjTime.duration(""));
            assertNull(DjTime.duration(null));
        }

        @Test
        @DisplayName("seconds() falls back rather than propagating a null")
        void secondsFallsBack() {
            assertEquals(18_000L, DjTime.seconds("5:00:00.000", -1));
            assertEquals(-1L, DjTime.seconds("rubbish", -1));
        }
    }

    @Nested
    @DisplayName("timestamps, ISO-8601 with an offset")
    class Timestamps {

        @Test
        @DisplayName("an offset that is not UTC is respected, not ignored")
        void withOffset() {
            // 09:00+02:00 is 07:00Z. Parsing this as if it were UTC would put the contest
            // start two hours late for everyone in the room.
            assertEquals(Instant.parse("2026-09-03T07:00:00Z"),
                DjTime.instant("2026-09-03T09:00:00+02:00"));
        }

        @Test
        @DisplayName("plain UTC still parses")
        void utc() {
            assertEquals(Instant.parse("2026-09-03T09:00:00Z"),
                DjTime.instant("2026-09-03T09:00:00+00:00"));
            assertEquals(Instant.parse("2026-09-03T09:00:00Z"),
                DjTime.instant("2026-09-03T09:00:00Z"));
        }

        @Test
        @DisplayName("null and nonsense come back null")
        void garbage() {
            assertNull(DjTime.instant(null));
            assertNull(DjTime.instant(""));
            assertNull(DjTime.instant("tomorrow"));
        }
    }
}
