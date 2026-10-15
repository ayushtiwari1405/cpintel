package com.cpintel.groups;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Whether somebody's contest monitoring is running <em>right now</em>.
 *
 * <p>{@link ViolationService} answers a different question, and the difference is the reason
 * this exists. A violation report says "this is what the lock saw", and it is submitted in
 * batches, after the fact, and retried. It is an evidence trail. None of that can answer
 * "is the lock running at this instant", which is what a submission gate needs — and the
 * absence of violations is exactly what a contestant who closed the monitor and a contestant
 * who never left the window have in common.
 *
 * <p>So the client sends a heartbeat on a short timer and this records the fact with a TTL
 * worth a few missed beats. A key that is present means something was reporting recently; a
 * key that has expired means nothing has been. Expiry rather than an explicit "stopped"
 * message is deliberate: a contestant who kills the tab, pulls the network cable or force-quits
 * the desktop app never gets to send a stop, and those are precisely the cases the gate is for.
 *
 * <p><b>This is not proof of anything.</b> A heartbeat says a client claimed to be monitoring,
 * and a determined contestant can send one without a lock behind it. It raises the cost of
 * sitting a monitored round unmonitored from "close the window" to "forge a request", which is
 * worth having, and it is not the same as integrity. The honest framing is the one the
 * violation trail already uses: this records observations, not proof.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContestMonitorRegistry {

    private static final String KEY_PREFIX = "monitor:";

    private final StringRedisTemplate redis;

    /**
     * How long one heartbeat vouches for.
     *
     * Several times the client's send interval, so an ordinary dropped request or a garbage
     * collection pause does not read as "the monitor stopped" and block a submission the
     * contestant was entitled to make. Too long and somebody who closed the lock keeps a valid
     * window; the default trades a few seconds of that against not failing honest submissions.
     */
    @Value("${cpintel.proctoring.heartbeat-ttl-seconds:45}")
    private long ttlSeconds;

    /** What the client is told to use, so both ends cannot drift apart. */
    @Value("${cpintel.proctoring.heartbeat-interval-seconds:15}")
    private long intervalSeconds;

    public long intervalSeconds() {
        return intervalSeconds;
    }

    private String key(Long contestId, Long userId) {
        return KEY_PREFIX + contestId + ":" + userId;
    }

    /** Records that this contestant's monitor was alive just now. */
    public void beat(Long userId, Long contestId) {
        try {
            redis.opsForValue().set(key(contestId, userId),
                String.valueOf(Instant.now().getEpochSecond()),
                Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            // Nothing to record it in; the gate below knows Redis is down and does not ask.
            log.debug("Heartbeat for user {} on {} not recorded: {}", userId, contestId,
                e.getMessage());
        }
    }

    /**
     * True while a heartbeat from this contestant is still within its window.
     *
     * <p>True as well when Redis cannot be read at all. The alternative refuses every
     * submission in every monitored paper for as long as Redis is down — a whole room unable to
     * hand in, for a fault none of them caused — and what it would guard against, a monitor
     * closed during the outage, is still in the examination log as the focus losses and
     * absences the client reports. After a restart Redis is merely empty, and the next beat,
     * at most one interval away, refills it.
     */
    public boolean isMonitored(Long userId, Long contestId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key(contestId, userId)));
        } catch (Exception e) {
            log.warn("Monitor check for user {} on {} skipped, Redis unavailable: {}",
                userId, contestId, e.getMessage());
            return true;
        }
    }

    /**
     * Forgets this contestant's monitor.
     *
     * Called when a round ends. Not strictly necessary given the TTL, but it keeps a finished
     * contest from holding a live-looking key for the last three quarters of a minute.
     */
    public void clear(Long userId, Long contestId) {
        redis.delete(key(contestId, userId));
    }
}
