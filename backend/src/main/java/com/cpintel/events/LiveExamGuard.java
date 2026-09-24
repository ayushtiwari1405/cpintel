package com.cpintel.events;

import com.cpintel.entity.GroupContest;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.GroupContestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Keeps a candidate's own stored material out of an examination they are sitting.
 *
 * <p>The contest routes already ask whether personal files are allowed — but the vault and the
 * code archive have routes of their own, used by Practice, that asked nothing. So "private
 * files: off" held on the compete page and nowhere else: a candidate could open their notes
 * from the vault, or load any old solution from "Everything I have written", mid-paper.
 *
 * <p>While one of a person's examinations is running, those general routes answer only for
 * that examination's own contest (the archive) or not at all (the vault — the contest route,
 * which applies the paper's rule, is the way in). A person is treated as sitting every live
 * examination they are assigned to: being assigned and elsewhere is not a state the paper
 * allows for.
 */
@Service
@RequiredArgsConstructor
public class LiveExamGuard {

    private final GroupContestRepository events;

    /** The examination this person is supposed to be sitting right now, if any. */
    @Transactional(readOnly = true)
    public Optional<GroupContest> liveExamFor(Long userId) {
        Instant now = Instant.now();
        return events.findAllForParticipant(userId).stream()
            .filter(GroupContest::isExam)
            .filter(e -> e.effectiveLifecycle(now) == GroupContest.Lifecycle.ACTIVE)
            .findFirst();
    }

    /** True unless a live examination rules this contest's material out. */
    public boolean allowsContest(Long userId, String platform, String contestId) {
        return liveExamFor(userId)
            .map(exam -> exam.getPlatform().equalsIgnoreCase(platform)
                && exam.getExternalId().equals(contestId))
            .orElse(true);
    }

    /** Throws when this contest's material is out of bounds during a live examination. */
    public void requireContestAllowed(Long userId, String platform, String contestId) {
        if (!allowsContest(userId, platform, contestId)) {
            throw ApiException.forbidden(
                "Your examination is running, so only code from this examination can be opened.");
        }
    }

    /**
     * Whether this was submitted while the paper was open — the only work that belongs to it.
     *
     * <p>A paper can be set on a judge contest that already held a practice round, so "this
     * contest" is not enough to say what is the candidate's own work on this paper: their
     * practice attempts, source included, sit under the same contest id. The window is.
     */
    public static boolean madeDuring(GroupContest exam, Instant at) {
        return at != null && exam.getStartsAt() != null && !at.isBefore(exam.getStartsAt())
            && (exam.getEndsAt() == null || !at.isAfter(exam.getEndsAt()));
    }

    /** The live examination set on this contest, if this person is sitting one. */
    public Optional<GroupContest> liveExamOn(Long userId, String platform, String contestId) {
        return liveExamFor(userId)
            .filter(exam -> exam.getPlatform().equalsIgnoreCase(platform)
                && exam.getExternalId().equals(contestId));
    }

    /**
     * Throws when a live examination rules this submission out: another contest's, or this
     * contest's from before the paper started.
     */
    public void requireSubmissionAllowed(Long userId, String platform, String contestId,
                                         Instant submittedAt) {
        requireContestAllowed(userId, platform, contestId);
        liveExamOn(userId, platform, contestId)
            .filter(exam -> !madeDuring(exam, submittedAt))
            .ifPresent(exam -> {
                throw ApiException.forbidden("Your examination is running, so only code "
                    + "written during it can be opened.");
            });
    }

    /** Throws while any examination is live — for routes with no contest to scope to. */
    public void requireNoLiveExam(Long userId, String what) {
        liveExamFor(userId).ifPresent(exam -> {
            throw ApiException.forbidden("\"" + exam.getName() + "\" is running, so " + what
                + " cannot be opened from here. Use the files panel inside the examination, "
                + "if it allows files.");
        });
    }
}
