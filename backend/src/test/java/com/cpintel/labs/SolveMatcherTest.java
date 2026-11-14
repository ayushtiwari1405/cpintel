package com.cpintel.labs;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SolveMatcherTest {

    private static final Instant OPENS = Instant.parse("2026-11-10T09:00:00Z");
    private static final Instant DUE = Instant.parse("2026-11-17T09:00:00Z");

    private static SolveMatcher.Pending pending(boolean countEarlier) {
        return new SolveMatcher.Pending(1L, "1700/A", OPENS, DUE, countEarlier);
    }

    private static SolveMatcher.Accepted at(String when, String ref) {
        return new SolveMatcher.Accepted("1700/A", Instant.parse(when), ref);
    }

    @Test
    void creditsASolveInsideTheWindow() {
        var found = SolveMatcher.match(List.of(pending(false)), List.of(at("2026-11-12T10:00:00Z", "s1")));
        assertThat(found).singleElement().satisfies(f -> {
            assertThat(f.late()).isFalse();
            assertThat(f.ref()).isEqualTo("s1");
        });
    }

    @Test
    void ignoresAnEarlierSolveUnlessTheLabCountsThem() {
        var earlier = List.of(at("2025-01-01T00:00:00Z", "old"));
        assertThat(SolveMatcher.match(List.of(pending(false)), earlier)).isEmpty();
        assertThat(SolveMatcher.match(List.of(pending(true)), earlier))
            .singleElement().satisfies(f -> assertThat(f.late()).isFalse());
    }

    @Test
    void marksASolveAfterTheDeadlineLate() {
        var found = SolveMatcher.match(List.of(pending(false)), List.of(at("2026-11-18T00:00:00Z", "s")));
        assertThat(found).singleElement().satisfies(f -> assertThat(f.late()).isTrue());
    }

    @Test
    void prefersInTimeOverLateThenTheEarliest() {
        var found = SolveMatcher.match(List.of(pending(false)), List.of(
            at("2026-11-18T00:00:00Z", "late"),
            at("2026-11-15T00:00:00Z", "second"),
            at("2026-11-11T00:00:00Z", "first"),
            at("2026-11-01T00:00:00Z", "before-opening")));
        assertThat(found).singleElement().satisfies(f -> assertThat(f.ref()).isEqualTo("first"));
    }

    @Test
    void ignoresOtherProblems() {
        var other = new SolveMatcher.Accepted("1700/B", Instant.parse("2026-11-12T00:00:00Z"), "x");
        assertThat(SolveMatcher.match(List.of(pending(false)), List.of(other))).isEmpty();
    }

    @Test
    void readsBackToTheEarliestOpeningOrForeverWhenEarlierSolvesCount() {
        assertThat(SolveMatcher.readBackTo(List.of(pending(false)))).isEqualTo(OPENS);
        assertThat(SolveMatcher.readBackTo(List.of(pending(false), pending(true)))).isEqualTo(Instant.EPOCH);
    }
}
