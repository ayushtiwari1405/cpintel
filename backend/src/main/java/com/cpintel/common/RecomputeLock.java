package com.cpintel.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Lets one caller at a time, across every instance, do an expensive recompute that everybody
 * else can wait out by serving the previous result.
 *
 * <p>Made for the leaderboards: two hundred candidates poll one board, and the moment its
 * snapshot falls due every one of them used to see "due" and recompute it — the same judge reads
 * and the same ranking, two hundred times, at exactly the busiest moment. With this, one does the
 * work and the rest keep reading the board they already had for the few seconds it takes.
 *
 * <p>{@code SET NX PX} to take it, so it is atomic and expires on its own if the holder dies; a
 * compare-and-delete script to give it back, so a holder whose lock has already expired cannot
 * release the next holder's. If Redis cannot be reached the lock is granted: the cost of that is
 * the old behaviour (several callers recompute), never a board nobody may compute.
 */
@Component
@Slf4j
public class RecomputeLock {

    private static final String PREFIX = "recompute:";

    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[1] then
            return redis.call('DEL', KEYS[1])
        end
        return 0
        """, Long.class);

    private final RedisTemplate<String, Object> redis;

    public RecomputeLock(RedisTemplate<String, Object> redis) {
        this.redis = redis;
    }

    /**
     * Runs {@code work} if this caller wins the lock for {@code key}, and returns its result;
     * otherwise returns null without running it, meaning somebody else is already on it.
     *
     * @param ttl how long the lock may be held at most — longer than the work ever takes
     */
    public <T> T ifFree(String key, Duration ttl, Supplier<T> work) {
        String token = UUID.randomUUID().toString();
        if (!acquire(key, token, ttl)) return null;
        try {
            return work.get();
        } finally {
            release(key, token);
        }
    }

    private boolean acquire(String key, String token, Duration ttl) {
        if (redis == null) return true;
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(PREFIX + key, token, ttl));
        } catch (Exception e) {
            log.debug("Recompute lock {} unavailable, going ahead: {}", key, e.getMessage());
            return true;
        }
    }

    private void release(String key, String token) {
        if (redis == null) return;
        try {
            redis.execute(RELEASE, List.of(PREFIX + key), token);
        } catch (Exception e) {
            // It expires on its own; the next recompute is at most one ttl late.
            log.debug("Could not release recompute lock {}: {}", key, e.getMessage());
        }
    }
}
