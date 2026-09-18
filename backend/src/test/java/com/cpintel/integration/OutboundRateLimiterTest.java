package com.cpintel.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import com.cpintel.config.AppMetrics;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * How a caller behaves once the shared timeline has told it when its slot is.
 *
 * <p>The reservation arithmetic itself runs inside Redis, so what is worth pinning down here is
 * everything around it: that a due slot costs nothing, that a queued one waits, that an
 * unreasonable one is refused rather than parking a pooled thread, and — the one that matters
 * most — that a Redis outage degrades to local pacing instead of to no pacing at all.
 */
class OutboundRateLimiterTest {

    private RedisTemplate<String, Object> redis;
    private OutboundRateLimiter limiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(RedisTemplate.class);
        limiter = new OutboundRateLimiter(redis, mock(AppMetrics.class));
    }

    @SuppressWarnings("unchecked")
    private void reservationReturns(Long waitMs) {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any()))
            .thenReturn(waitMs);
    }

    @SuppressWarnings("unchecked")
    private void reservationFails() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any()))
            .thenThrow(new RedisConnectionFailureException("down"));
    }

    @Nested
    @DisplayName("Waiting for a slot")
    class Waiting {

        @Test
        @DisplayName("a slot that is already due costs nothing")
        void dueSlotDoesNotWait() {
            reservationReturns(0L);

            long before = System.currentTimeMillis();
            limiter.acquire("codeforces", 500, 30_000);

            assertTrue(System.currentTimeMillis() - before < 200,
                "an available slot should not sleep");
        }

        @Test
        @DisplayName("a queued slot waits for its turn")
        void queuedSlotWaits() {
            reservationReturns(250L);

            long before = System.currentTimeMillis();
            limiter.acquire("codeforces", 500, 30_000);

            assertTrue(System.currentTimeMillis() - before >= 240,
                "a reserved slot in the future should be waited for");
        }

        @Test
        @DisplayName("an interval of zero disables pacing without touching Redis")
        void zeroIntervalSkipsEntirely() {
            limiter.acquire("codeforces", 0, 30_000);
            verifyNoInteractions(redis);
        }
    }

    @Nested
    @DisplayName("Backpressure")
    class Backpressure {

        @Test
        @DisplayName("a queue longer than the caller can afford is refused, not slept through")
        void refusesUnreasonableWait() {
            reservationReturns(120_000L);

            var e = assertThrows(OutboundRateLimiter.OutboundBackpressureException.class,
                () -> limiter.acquire("codeforces", 500, 30_000));

            assertTrue(e.getMessage().contains("codeforces"), e.getMessage());
            assertTrue(e.getMessage().contains("120s"), e.getMessage());
        }

        @Test
        @DisplayName("a wait exactly at the limit is still honoured")
        void allowsWaitAtTheBoundary() {
            reservationReturns(50L);
            assertDoesNotThrow(() -> limiter.acquire("codeforces", 500, 50));
        }
    }

    @Nested
    @DisplayName("When Redis is unavailable")
    class DegradesLocally {

        @Test
        @DisplayName("it paces locally rather than letting the calls through unpaced")
        void fallsBackToLocalPacing() {
            reservationFails();

            long before = System.currentTimeMillis();
            limiter.acquire("codeforces", 300, 30_000);

            assertTrue(System.currentTimeMillis() - before >= 290,
                "losing Redis must not turn into an unpaced flood at the judge");
        }

        @Test
        @DisplayName("the fallback serialises callers, so two threads do not both go at once")
        void localFallbackSerialisesCallers() throws Exception {
            reservationFails();

            long before = System.currentTimeMillis();
            var a = new Thread(() -> limiter.acquire("codeforces", 200, 30_000));
            var b = new Thread(() -> limiter.acquire("codeforces", 200, 30_000));
            a.start(); b.start();
            a.join(); b.join();

            // Serialised: ~400ms for two. Unserialised they would overlap at ~200ms, which is
            // precisely the bug the old per-thread Thread.sleep had.
            assertTrue(System.currentTimeMillis() - before >= 390,
                "two callers to one platform must not be paced concurrently");
        }

        @Test
        @DisplayName("different platforms are not held up behind each other")
        void differentPlatformsDoNotBlock() throws Exception {
            reservationFails();

            long before = System.currentTimeMillis();
            var a = new Thread(() -> limiter.acquire("codeforces", 300, 30_000));
            var b = new Thread(() -> limiter.acquire("leetcode", 300, 30_000));
            a.start(); b.start();
            a.join(); b.join();

            assertTrue(System.currentTimeMillis() - before < 560,
                "separate judges have separate quotas and should run in parallel");
        }
    }
}
