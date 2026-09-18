package com.cpintel.integration;

import com.cpintel.config.AppMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paces outbound calls to a third-party judge, across every thread and every instance.
 *
 * <p>What this replaces was {@code Thread.sleep(rateLimitMs)} at the top of each client method.
 * That paces one thread against itself and nothing else: the sync executor runs up to eight
 * threads, so eight of them each sleeping half a second independently produced roughly eight
 * times the configured rate. Codeforces answers sustained abuse with an IP ban, which takes
 * sync down for every user of the deployment at once — a shared quota needs a shared limiter.
 *
 * <p>The mechanism is a reservation rather than a token bucket. Each caller atomically claims
 * the next free slot on a shared timeline and is told how long to wait for it, so callers queue
 * in arrival order and the spacing between any two requests to one platform holds no matter how
 * many threads or replicas are asking. A bucket would allow a burst to drain instantly, which is
 * exactly what these APIs object to.
 *
 * <p>The reservation has to be atomic — read, compare, write from several instances at once is a
 * race that hands two callers the same slot — hence the Lua script, which Redis runs to
 * completion without interleaving.
 *
 * <p><b>On failure it degrades to the old behaviour</b> rather than to none: if Redis is
 * unreachable the caller sleeps the interval locally, which is what the code did before this
 * class existed. That keeps a cache outage from turning into an unpaced flood at the judge.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboundRateLimiter {

    private static final String PREFIX = "outbound:slot:";

    /**
     * Claims the next slot and returns the wait in milliseconds.
     *
     * {@code slot} holds the earliest time the next call may go out. A caller takes
     * {@code max(now, slot)}, pushes the marker one interval further, and waits for the
     * difference. The TTL only stops an idle platform's key living forever; it is always
     * comfortably longer than one interval.
     */
    private static final RedisScript<Long> RESERVE = new DefaultRedisScript<>("""
        local key      = KEYS[1]
        local now      = tonumber(ARGV[1])
        local interval = tonumber(ARGV[2])
        local ttl      = tonumber(ARGV[3])

        local slot = tonumber(redis.call('GET', key)) or 0
        local start = math.max(now, slot)

        redis.call('SET', key, start + interval, 'PX', ttl)
        return start - now
        """, Long.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final AppMetrics metrics;

    /** Falls back to per-process pacing when Redis cannot be reached. */
    private final Map<String, Object> localLocks = new ConcurrentHashMap<>();

    /**
     * Waits until this platform's next slot is due.
     *
     * @param platform  the judge being called — its own timeline
     * @param intervalMs minimum spacing between two calls to it
     * @param maxWaitMs  give up rather than hold a thread indefinitely behind a long queue
     */
    public void acquire(String platform, long intervalMs, long maxWaitMs) {
        if (intervalMs <= 0) return;

        long waitMs;
        try {
            Long reserved = redisTemplate.execute(
                RESERVE,
                List.of(PREFIX + platform),
                System.currentTimeMillis(), intervalMs, Math.max(intervalMs * 20, 60_000L));
            waitMs = reserved == null ? intervalMs : reserved;

        } catch (Exception e) {
            log.debug("Outbound limiter unavailable for {} — pacing locally: {}",
                platform, e.getMessage());
            paceLocally(platform, intervalMs);
            return;
        }

        if (waitMs <= 0) return;

        metrics.outboundWait(platform, java.time.Duration.ofMillis(waitMs));

        if (waitMs > maxWaitMs) {
            metrics.outboundBackpressure(platform);
            // The queue for this platform is longer than the caller is willing to wait. Saying
            // so beats holding a pooled thread for minutes; the slot stays reserved, so the
            // pacing is not broken by giving up on it.
            throw new OutboundBackpressureException(platform, waitMs);
        }

        sleep(waitMs);
    }

    /** Pacing that still works with Redis down: one thread at a time, spaced by the interval. */
    private void paceLocally(String platform, long intervalMs) {
        synchronized (localLocks.computeIfAbsent(platform, k -> new Object())) {
            sleep(intervalMs);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Raised when the wait for a slot is longer than the caller can afford. */
    public static class OutboundBackpressureException extends RuntimeException {
        public OutboundBackpressureException(String platform, long waitMs) {
            super("The " + platform + " request queue is " + (waitMs / 1000)
                + "s long. Try again shortly.");
        }
    }
}
