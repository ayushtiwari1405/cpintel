package com.cpintel.events;

import com.cpintel.entity.GroupContest;
import com.cpintel.repository.jpa.GroupContestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Signing in to an examination with the examination password on a candidate's slip.
 *
 * <p>The sign-in page takes either password. The account password opens ordinary mode; the code
 * issued for a paper opens examination mode for that paper — a session that reaches nothing
 * else, and the only kind that can reach the paper while it runs. Issued fresh for each paper
 * and printed for the room, it is useless before the paper's sign-in window and after it ends.
 */
@Service
@RequiredArgsConstructor
public class ExamLoginService {

    /** How long after the end an examination session can still renew itself. */
    public static final Duration AFTER_END = Duration.ofMinutes(15);

    private final GroupContestRepository events;
    private final ExamPasswordService passwords;

    /**
     * How long before the start an examination password starts to work: long enough for a room
     * to sign in and sit waiting, which the workspace is built for.
     */
    @Value("${cpintel.exams.sign-in-lead-minutes:60}")
    private long leadMinutes = 60;

    /** The paper this password opens for this person right now, if any. */
    @Transactional
    public Optional<GroupContest> match(Long userId, String password) {
        if (password == null || password.isBlank()) return Optional.empty();
        Instant now = Instant.now();
        for (GroupContest exam : events.findAllForParticipant(userId)) {
            if (!exam.isExam() || exam.getStartsAt() == null || exam.getEndsAt() == null) continue;
            GroupContest.Lifecycle stage = exam.effectiveLifecycle(now);
            if (stage != GroupContest.Lifecycle.SCHEDULED
                && stage != GroupContest.Lifecycle.ACTIVE) continue;
            if (now.isBefore(exam.getStartsAt().minus(Duration.ofMinutes(leadMinutes)))) continue;
            if (passwords.matchesPasscode(exam.getContestId(), userId, password)) {
                return Optional.of(exam);
            }
        }
        return Optional.empty();
    }

    /** Whether an examination session for this paper may still be renewed. */
    public boolean stillOpen(GroupContest exam, Instant now) {
        return exam.getEndsAt() != null && now.isBefore(exam.getEndsAt().plus(AFTER_END))
            && exam.effectiveLifecycle(now) != GroupContest.Lifecycle.ARCHIVED
            && exam.effectiveLifecycle(now) != GroupContest.Lifecycle.DRAFT;
    }
}
