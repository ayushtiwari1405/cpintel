package com.cpintel.events;

import com.cpintel.entity.ExamEvent;
import com.cpintel.entity.GroupContest;
import com.cpintel.exception.ApiException;
import com.cpintel.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Whether this candidate may be inside this examination right now.
 *
 * <p>Three things have to be true, and they are three separate facts about three different
 * moments:
 *
 * <ol>
 *   <li><b>They were assigned it</b> — decided days ago, by an admin, and checked by
 *       {@link EventService#requireAssigned}.</li>
 *   <li><b>The paper's window is open</b> — decided by the clock, and by nothing anybody can
 *       press.</li>
 *   <li><b>They unlocked it with the passwords handed out in the room</b> — decided at the
 *       desk, by the invigilator, at the moment the paper starts.</li>
 * </ol>
 *
 * <p>The third is what this class adds, and it is the one the other two cannot supply. An
 * assignment says somebody is expected; it cannot say they are in the room. A clock says the
 * paper is open; it cannot say it is open <em>for this person, here</em>. Without a password
 * the whole of "sitting the examination" is "be on the roster and have a browser", which is
 * exactly the arrangement an invigilated paper exists to replace.
 *
 * <h2>The grant</h2>
 *
 * <p>Unlocking mints a grant in Redis, keyed by examination and candidate, which lives until
 * the paper's window closes. It exists so a candidate types the codes once rather than on
 * every request, and it carries the password generation it was issued under — so rotating a
 * leaked password ends the sessions it opened, rather than merely inconveniencing people who
 * had not got in yet.
 *
 * <p>Redis rather than a column, for the same reason the monitor heartbeat is in Redis: it is
 * state about a session in progress, it must vanish on its own when the paper ends, and
 * nothing afterwards wants to read it. The examination's own event log records the unlock,
 * which is the part worth keeping.
 *
 * <p><b>It is not proof of anything.</b> A candidate can read their code to somebody else down
 * a phone, the way they could read a question out. What this makes impossible is sitting the
 * paper without anybody in the room handing you anything, which is the failure it was built
 * for — and the session log stays the thing an invigilator actually reads.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExamAccessService {

    private static final String KEY_PREFIX = "exam:access:";

    /**
     * How long a grant outlives the paper.
     *
     * A few minutes, so that a candidate submitting in the last second of the window is not
     * refused by a grant that expired a moment before the clock did. Nothing can be submitted
     * past the window anyway — the lifecycle check is separate and unconditional — so this
     * only ever removes a spurious refusal.
     */
    private static final Duration GRACE = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;
    private final ExamPasswordService passwords;
    private final ExamEventService examEvents;
    private final AuditService auditService;

    private String key(Long examId, Long userId) {
        return KEY_PREFIX + examId + ":" + userId;
    }

    // ------------------------------------------------------------ questions

    /** True when this examination will ask this candidate for anything at all. */
    public boolean requiresUnlock(GroupContest exam, Long userId) {
        if (!exam.isExam()) return false;
        return passwords.requiresExamPassword(exam)
            || passwords.requiresPasscode(exam.getContestId(), userId);
    }

    /**
     * True when this candidate currently holds a valid grant for this paper.
     *
     * <p>An examination that asks for no password is always unlocked, which is deliberate: an
     * admin who generated nothing has not decided to require anything, and inventing the
     * requirement would shut a room out of a paper that was about to start.
     */
    public boolean isUnlocked(GroupContest exam, Long userId) {
        if (!requiresUnlock(exam, userId)) return true;

        String stored = redis.opsForValue().get(key(exam.getContestId(), userId));
        if (stored == null) return false;

        // The grant names the password generation it was minted under. A rotation moves the
        // examination past it, and every grant from before is stale in the same instant.
        int generation = exam.getExamPasswordGen() == null ? 0 : exam.getExamPasswordGen();
        try {
            return Integer.parseInt(stored) == generation;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // --------------------------------------------------------------- unlock

    /**
     * Checks what the candidate typed and, if it is right, lets them in for the sitting.
     *
     * <p>Refused before the window opens and after it closes, because a password is not a key
     * to a paper that is not running — somebody who obtained one early must not be able to
     * read the questions before the room does.
     */
    public void unlock(GroupContest exam, Long userId, String examPassword, String passcode,
                       HttpServletRequest httpReq) {
        Instant now = Instant.now();

        if (!exam.isOpenForParticipation(now)) {
            throw ApiException.forbidden(
                "This examination is not open. It can only be unlocked while its window is "
                + "running — wait for your invigilator to start it.");
        }

        if (!requiresUnlock(exam, userId)) {
            // Nothing to check. Recorded as unlocked anyway so the session log reads the same
            // for every candidate, whether or not this paper used passwords.
            grant(exam, userId);
            return;
        }

        if (!passwords.verify(exam, userId, examPassword, passcode)) {
            auditService.record(userId, AuditService.EXAM_UNLOCK_REFUSED, "EXAM",
                String.valueOf(exam.getContestId()), httpReq);
            examEvents.recordServerSide(exam, userId, ExamEvent.Type.SUSPICIOUS_ACTIVITY, null,
                "Wrong examination password");
            log.info("Refused an examination unlock from user {} on exam {}",
                userId, exam.getContestId());

            // Which half was wrong is not said. The candidate has both slips in front of them
            // and should try both again; telling somebody who has one of the two which one
            // they are missing turns the pair into two independent guesses.
            throw ApiException.forbidden(
                "That did not match. Check the examination password your invigilator gave the "
                + "room and the code on your own slip, and try again.");
        }

        grant(exam, userId);
        auditService.record(userId, AuditService.EXAM_UNLOCKED, "EXAM",
            String.valueOf(exam.getContestId()), httpReq);
        examEvents.recordServerSide(exam, userId, ExamEvent.Type.EXAM_STARTED, null,
            "Unlocked the examination with the password issued in the room");
        log.info("User {} unlocked exam {}", userId, exam.getContestId());
    }

    /**
     * Refuses a candidate who has not unlocked this paper.
     *
     * <p>Called by every route that reads or writes anything inside a live examination. The
     * message names what to do, because the person reading it is sitting an exam and a bare
     * "forbidden" would send them looking for an invigilator over something they can fix by
     * typing a code they already have.
     */
    public void requireUnlocked(GroupContest exam, Long userId) {
        if (isUnlocked(exam, userId)) return;
        throw ApiException.forbidden(
            "This examination has not been unlocked on this device. Enter the examination "
            + "password your invigilator gave the room, together with the code on your own "
            + "slip.");
    }

    /**
     * Ends this candidate's access, without touching anybody else's.
     *
     * For a candidate who has to be moved to another machine, or one an invigilator is
     * removing from the room.
     */
    public void revoke(Long examId, Long userId) {
        redis.delete(key(examId, userId));
    }

    private void grant(GroupContest exam, Long userId) {
        Duration ttl = GRACE;
        if (exam.getEndsAt() != null) {
            Duration left = Duration.between(Instant.now(), exam.getEndsAt());
            if (!left.isNegative()) ttl = left.plus(GRACE);
        }
        int generation = exam.getExamPasswordGen() == null ? 0 : exam.getExamPasswordGen();
        redis.opsForValue().set(key(exam.getContestId(), userId),
            String.valueOf(generation), ttl);
    }
}
