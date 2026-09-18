package com.cpintel.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The counter itself. The rules it enforces are only worth as much as its two edges: it must
 * stop the caller who is over the limit, and it must never become an outage of its own.
 */
class RateLimitServiceTest {

    private RedisTemplate<String, Object> redis;
    private ValueOperations<String, Object> values;
    private RateLimitProperties props;
    private RateLimitService service;

    /** Stands in for Redis so a window can be counted without one running. */
    private Map<String, Long> counters;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(RedisTemplate.class);
        values = mock(ValueOperations.class);
        counters = new HashMap<>();

        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenAnswer(inv ->
            counters.merge(inv.getArgument(0), 1L, Long::sum));
        when(redis.getExpire(anyString())).thenReturn(60L);

        props = new RateLimitProperties();
        service = new RateLimitService(redis, props);
    }

    private RateLimitProperties.Rule rule(int limit) {
        return new RateLimitProperties.Rule(limit, Duration.ofMinutes(1));
    }

    @Nested
    @DisplayName("Counting")
    class Counting {

        @Test
        @DisplayName("allows up to the limit and refuses the one after it")
        void allowsUpToLimit() {
            for (int i = 1; i <= 5; i++) {
                assertTrue(service.tryAcquire("login", "1.2.3.4", rule(5)),
                    "attempt " + i + " should be allowed");
            }
            assertFalse(service.tryAcquire("login", "1.2.3.4", rule(5)));
        }

        @Test
        @DisplayName("keys are independent, so one caller cannot lock out another")
        void keysAreIndependent() {
            for (int i = 0; i < 5; i++) service.tryAcquire("login", "attacker", rule(5));

            assertFalse(service.tryAcquire("login", "attacker", rule(5)));
            assertTrue(service.tryAcquire("login", "someone-else", rule(5)));
        }

        @Test
        @DisplayName("buckets are independent, so signing in does not consume a run")
        void bucketsAreIndependent() {
            for (int i = 0; i < 5; i++) service.tryAcquire("login", "same-key", rule(5));

            assertFalse(service.tryAcquire("login", "same-key", rule(5)));
            assertTrue(service.tryAcquire("run", "same-key", rule(5)));
        }

        @Test
        @DisplayName("the window is opened on the first request in it")
        void setsExpiryOnFirstRequest() {
            service.tryAcquire("login", "1.2.3.4", rule(5));
            verify(redis).expire(eq("ratelimit:login:1.2.3.4"), eq(Duration.ofMinutes(1)));
        }

        @Test
        @DisplayName("a key left without an expiry is repaired rather than blocking forever")
        void repairsMissingExpiry() {
            when(redis.getExpire(anyString())).thenReturn(-1L);

            service.tryAcquire("login", "1.2.3.4", rule(5));   // opens the window
            service.tryAcquire("login", "1.2.3.4", rule(5));   // finds no TTL, sets one again

            verify(redis, times(2)).expire(anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("a successful sign-in clears the caller's attempts")
        void resetClearsTheCounter() {
            service.reset("login-account", "someone@example.com");
            verify(redis).delete("ratelimit:login-account:someone@example.com");
        }
    }

    @Nested
    @DisplayName("When Redis is unavailable")
    class FailsOpen {

        @Test
        @DisplayName("the request is allowed rather than refused")
        void allowsOnRedisFailure() {
            when(values.increment(anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));

            assertTrue(service.tryAcquire("login", "1.2.3.4", rule(1)));
        }

        @Test
        @DisplayName("Retry-After still answers with something usable")
        void retryAfterSurvivesFailure() {
            when(redis.getExpire(anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));

            assertEquals(1, service.retryAfterSeconds("login", "1.2.3.4"));
        }

        @Test
        @DisplayName("reset does not propagate the failure to the sign-in it follows")
        void resetSwallowsFailure() {
            when(redis.delete(anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));

            assertDoesNotThrow(() -> service.reset("login-account", "someone@example.com"));
        }
    }

    @Nested
    @DisplayName("Switches")
    class Switches {

        @Test
        @DisplayName("disabling the feature stops it counting at all")
        void disabledAllowsEverything() {
            props.setEnabled(false);

            for (int i = 0; i < 50; i++) {
                assertTrue(service.tryAcquire("login", "1.2.3.4", rule(1)));
            }
            verifyNoInteractions(values);
        }

        @Test
        @DisplayName("a blank key is not counted, so one anonymous bucket cannot collect everyone")
        void blankKeyIsNotCounted() {
            assertTrue(service.tryAcquire("run", null, rule(1)));
            assertTrue(service.tryAcquire("run", "", rule(1)));
            verifyNoInteractions(values);
        }
    }
}
