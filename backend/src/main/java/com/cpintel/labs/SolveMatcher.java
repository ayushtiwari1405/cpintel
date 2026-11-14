package com.cpintel.labs;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which of a student's accepted submissions credit which lab problems.
 *
 * <p>Pure: handed the problems still open for the student and the accepted submissions read
 * from one platform, it says what to record. For each problem the best submission wins — one in
 * time over a late one, then the earliest. A submission:
 * <ul>
 *   <li>after the deadline is late;</li>
 *   <li>between opening and the deadline is in time;</li>
 *   <li>before opening is in time only when the lab counts earlier solves, and ignored
 *       otherwise.</li>
 * </ul>
 */
public final class SolveMatcher {

    private SolveMatcher() {}

    /** A problem still unsolved by this student, in the lab that sets it. */
    public record Pending(Long problemId, String key, Instant opensAt, Instant dueAt,
                          boolean countEarlier) {}

    /** An accepted submission: the problem key it was for, when, and the platform's id. */
    public record Accepted(String key, Instant at, String ref) {}

    public record Found(Long problemId, Instant at, boolean late, String ref) {}

    public static List<Found> match(Collection<Pending> pending, Collection<Accepted> accepted) {
        Map<String, List<Accepted>> byKey = new HashMap<>();
        for (Accepted a : accepted) byKey.computeIfAbsent(a.key(), k -> new ArrayList<>()).add(a);

        List<Found> found = new ArrayList<>();
        for (Pending p : pending) {
            Found best = null;
            for (Accepted a : byKey.getOrDefault(p.key(), List.of())) {
                Found candidate = judge(p, a);
                if (candidate != null && better(candidate, best)) best = candidate;
            }
            if (best != null) found.add(best);
        }
        return found;
    }

    static Found judge(Pending p, Accepted a) {
        if (a.at().isAfter(p.dueAt())) return new Found(p.problemId(), a.at(), true, a.ref());
        if (!a.at().isBefore(p.opensAt()) || p.countEarlier()) {
            return new Found(p.problemId(), a.at(), false, a.ref());
        }
        return null;
    }

    private static boolean better(Found candidate, Found best) {
        if (best == null) return true;
        if (candidate.late() != best.late()) return !candidate.late();
        return candidate.at().isBefore(best.at());
    }

    /** How far back a platform's history has to be read for these problems. */
    public static Instant readBackTo(Collection<Pending> pending) {
        Instant earliest = null;
        for (Pending p : pending) {
            Instant bound = p.countEarlier() ? Instant.EPOCH : p.opensAt();
            if (earliest == null || bound.isBefore(earliest)) earliest = bound;
        }
        return earliest == null ? Instant.now() : earliest;
    }
}
