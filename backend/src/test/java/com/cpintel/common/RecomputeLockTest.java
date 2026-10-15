package com.cpintel.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecomputeLockTest {

    private RedisTemplate<String, Object> redis;
    private ValueOperations<String, Object> values;
    private RecomputeLock lock;
    private final AtomicInteger runs = new AtomicInteger();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(RedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        lock = new RecomputeLock(redis);
    }

    private String work() {
        runs.incrementAndGet();
        return "board";
    }

    @Test
    @DisplayName("the caller that wins runs the work, then gives the lock back")
    void winnerRuns() {
        when(values.setIfAbsent(eq("recompute:lb"), anyString(), any(Duration.class))).thenReturn(true);

        assertEquals("board", lock.ifFree("lb", Duration.ofMinutes(1), this::work));
        assertEquals(1, runs.get());
        verify(redis).execute(any(RedisScript.class), anyList(), anyString());
    }

    @Test
    @DisplayName("everyone else is told somebody is on it, and does nothing")
    void loserWaits() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertNull(lock.ifFree("lb", Duration.ofMinutes(1), this::work));
        assertEquals(0, runs.get());
    }

    @Test
    @DisplayName("an unreachable Redis grants the lock rather than leaving nobody to compute")
    void redisDownGrants() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenThrow(new RedisConnectionFailureException("down"));

        assertEquals("board", lock.ifFree("lb", Duration.ofMinutes(1), this::work));
        assertEquals(1, runs.get());
    }

    @Test
    @DisplayName("the lock is given back even when the work throws")
    void releasedOnFailure() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> lock.ifFree("lb", Duration.ofMinutes(1),
            () -> { throw new IllegalStateException("judge down"); }));
        verify(redis).execute(any(RedisScript.class), anyList(), anyString());
    }
}
