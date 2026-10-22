package com.cpintel.analytics;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Which overdue skills are today's revision, and in what order the rest follow.
 *
 * <p>Decay is a half-life on time away, so a user back from a month off finds most of what they
 * know faded past the threshold at once: one seeded account had 21 skills due together, each
 * with three problems. Listing them all as due made the queue something to give up on.
 *
 * <p>So each UTC day offers at most {@value #SLOTS_PER_DAY} revisions, fewer by however many the
 * user has already completed that day. Every other overdue skill is still listed, as queued and
 * with its place in line; nothing overdue is hidden. Order is by priority: how far the skill has
 * faded, plus {@value #AGE_WEIGHT} points for every whole day it has been overdue. The age term
 * is what stops a mildly faded skill from waiting forever behind newly faded ones - decay is a
 * mastery percentage, so within (100 - its decay) / {@value #AGE_WEIGHT} days of waiting it
 * outranks anything that fades after it.
 *
 * <p>Nothing here moves a date. Reading the queue, however often, changes nothing; only marking
 * a revision done does, and that is what frees the next slot.
 */
public final class RevisionQueue {

    private RevisionQueue() {}

    /** Revisions offered per UTC day. */
    public static final int SLOTS_PER_DAY = 4;

    /** Priority, in decay points, an overdue skill gains for each whole day it waits. */
    public static final double AGE_WEIGHT = 5.0;

    public enum Status { DUE, QUEUED }

    /** One overdue skill: when it fell due and how far it has faded. */
    public record Candidate(String key, double decay, Instant dueAt) {}

    /**
     * @param position place in today's order, from 1; due ones come first
     */
    public record Placed(Candidate candidate, Status status, int position, double priority) {}

    /** Whole days since it fell due; zero on the day itself. */
    static long daysOverdue(Instant dueAt, Instant now) {
        return Math.max(0, Duration.between(dueAt, now).toDays());
    }

    static double priority(Candidate c, Instant now) {
        return c.decay() + AGE_WEIGHT * daysOverdue(c.dueAt(), now);
    }

    /** Slots still open today, given how many revisions were completed since UTC midnight. */
    public static int openSlots(int completedToday) {
        return Math.max(0, SLOTS_PER_DAY - Math.max(0, completedToday));
    }

    /** Start of the UTC day that {@code now} falls in. */
    public static Instant startOfDay(Instant now) {
        return LocalDate.ofInstant(now, ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * Orders the overdue skills and marks the first {@code openSlots} as due. Ties go to the one
     * due longer, then to the key, so the same inputs always give the same queue.
     */
    public static List<Placed> arrange(List<Candidate> overdue, Instant now, int openSlots) {
        List<Candidate> sorted = new ArrayList<>(overdue);
        sorted.sort(Comparator.comparingDouble((Candidate c) -> -priority(c, now))
            .thenComparing(Candidate::dueAt)
            .thenComparing(Candidate::key));
        List<Placed> out = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            Candidate c = sorted.get(i);
            out.add(new Placed(c, i < openSlots ? Status.DUE : Status.QUEUED, i + 1, priority(c, now)));
        }
        return out;
    }
}
