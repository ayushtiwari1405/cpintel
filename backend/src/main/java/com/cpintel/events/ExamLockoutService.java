package com.cpintel.events;

import com.cpintel.entity.GroupContest;
import com.cpintel.repository.jpa.ContestAssignmentRepository;
import com.cpintel.repository.jpa.GroupContestRepository;
import com.cpintel.repository.jpa.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Keeps a candidate out of ordinary mode for as long as their examination is running.
 *
 * <p>{@link ExamSessionGuard} closes the paper to ordinary sessions; this closes everything else
 * to its candidates. From the moment a paper starts until the moment it ends, somebody assigned
 * to it — through their team or by name — cannot sign in with their account password, cannot
 * renew an ordinary session, and has any ordinary session they were already holding ended. The
 * only way in is the examination password on their slip, which opens that one paper and nothing
 * else. When the window closes the rule lifts by itself.
 *
 * <p>Held to the paper's own window, not the sign-in lead before it or the grace after it: a
 * candidate may still read their notes up to the start, and is back in ordinary mode the moment
 * the paper is over. Admins are not candidates and are not held to it. A public examination
 * names nobody, so it locks nobody out; only assigned candidates are held.
 *
 * <p>Asked on every authenticated request, so the answer comes from a snapshot rather than the
 * database: the examinations that are running or about to start, with their candidates, read
 * again every {@link #REFRESH}. The window itself is checked against the clock on each request,
 * so a paper locks its room at its start time to the second; only a change made by an admin to
 * a running paper — a candidate added, the end moved — takes up to one refresh to be seen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExamLockoutService {

    /** How stale the snapshot may get. */
    static final Duration REFRESH = Duration.ofSeconds(30);

    /**
     * How far ahead the snapshot looks. Comfortably more than one refresh, so a paper starting
     * between two reads is already in the snapshot when its start time arrives.
     */
    private static final Duration HORIZON = Duration.ofMinutes(5);

    private final GroupContestRepository events;
    private final ContestAssignmentRepository assignments;
    private final RefreshTokenRepository refreshTokens;

    /** A running or imminent examination and who is sitting it. */
    public record Lock(Long examId, String name, Instant startsAt, Instant endsAt,
                       Set<Long> candidates) {
        boolean holds(Long userId, Instant now) {
            return !now.isBefore(startsAt) && now.isBefore(endsAt) && candidates.contains(userId);
        }
    }

    private record Snapshot(Instant readAt, List<Lock> locks) {}

    private volatile Snapshot snapshot;

    /**
     * The examination holding this person out of ordinary mode right now, if any.
     *
     * <p>Callers exempt admins themselves; this knows only who was assigned what.
     */
    public Optional<Lock> lockFor(Long userId) {
        if (userId == null) return Optional.empty();
        Instant now = Instant.now();
        for (Lock lock : current(now)) {
            if (lock.holds(userId, now)) return Optional.of(lock);
        }
        return Optional.empty();
    }

    /**
     * Ends the ordinary sessions of everybody sitting a running examination.
     *
     * <p>The request filter already refuses their access tokens, but a refresh token left alive
     * would sign them straight back in the moment the paper ended, having never been signed out
     * at all. Revoking it makes the sign-out real. Idempotent, so every instance running it is
     * harmless, and it only touches the database while a paper is running.
     */
    @Scheduled(fixedDelayString = "${cpintel.exams.lockout-sweep-ms:30000}")
    @Transactional
    public void endOrdinarySessions() {
        Instant now = Instant.now();
        for (Lock lock : current(now)) {
            if (now.isBefore(lock.startsAt()) || !now.isBefore(lock.endsAt())) continue;
            if (lock.candidates().isEmpty()) continue;
            int ended = refreshTokens.revokeOrdinarySessions(lock.candidates());
            if (ended > 0) {
                log.info("Signed {} ordinary session(s) out for examination {}",
                    ended, lock.examId());
            }
        }
    }

    private List<Lock> current(Instant now) {
        Snapshot s = snapshot;
        if (s != null && s.readAt().plus(REFRESH).isAfter(now)) return s.locks();
        synchronized (this) {
            s = snapshot;
            if (s != null && s.readAt().plus(REFRESH).isAfter(now)) return s.locks();
            s = new Snapshot(now, read(now));
            snapshot = s;
            return s.locks();
        }
    }

    private List<Lock> read(Instant now) {
        return events.findExamsOverlapping(now, now.plus(HORIZON)).stream()
            .filter(GroupContest::isExam)
            .map(exam -> {
                Set<Long> candidates = new HashSet<>(
                    assignments.participantsByTeam(exam.getContestId()));
                candidates.addAll(assignments.participantsNamedDirectly(exam.getContestId()));
                return new Lock(exam.getContestId(), exam.getName(), exam.getStartsAt(),
                    exam.getEndsAt(), Set.copyOf(candidates));
            })
            .toList();
    }
}
