package com.cpintel.integration.domjudge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A student in two classrooms holds two judge logins, and neither may replace the other. Before
 * classrooms the key was the user alone, and attaching the second login silently overwrote the
 * first — the bug these tests pin shut.
 */
class DomjudgeCredentialStoreTest {

    private static final Long USER = 42L;

    private final Map<String, String> redisStore = new HashMap<>();
    private DomjudgeCredentialStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(c -> redisStore.get(c.<String>getArgument(0)));
        doAnswer(c -> redisStore.put(c.getArgument(0), c.getArgument(1)))
            .when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.hasKey(anyString())).thenAnswer(c -> redisStore.containsKey(c.<String>getArgument(0)));
        when(redis.delete(anyString())).thenAnswer(c ->
            redisStore.remove(c.<String>getArgument(0)) != null);

        store = new DomjudgeCredentialStore(redis);
        ReflectionTestUtils.setField(store, "credentialKey", "test-key");
        ReflectionTestUtils.setField(store, "credentialTtlDays", 30L);
    }

    private DomjudgeCredentialStore.Stored login(String username, String team) {
        return new DomjudgeCredentialStore.Stored(username, "pw-" + username, null, team, team,
            null, null, Instant.EPOCH);
    }

    @Test
    @DisplayName("two classrooms keep two logins side by side")
    void loginsAreIsolatedPerClassroom() {
        store.save(1L, USER, login("asha", "t1"));
        store.save(2L, USER, login("asha", "t9"));

        assertEquals("t1", store.find(1L, USER).teamId());
        assertEquals("t9", store.find(2L, USER).teamId());
        assertNull(store.find(3L, USER));
    }

    @Test
    @DisplayName("detaching one classroom's login leaves the other")
    void deleteIsScoped() {
        store.save(1L, USER, login("asha", "t1"));
        store.save(2L, USER, login("asha", "t9"));

        store.delete(1L, USER);

        assertNull(store.find(1L, USER));
        assertTrue(store.exists(2L, USER));
    }

    @Test
    @DisplayName("what is at rest is not the password")
    void encryptedAtRest() {
        store.save(1L, USER, login("asha", "t1"));
        assertTrue(redisStore.values().stream().noneMatch(v -> v.contains("pw-asha")));
    }

    @Test
    @DisplayName("seal and open round-trip a classroom's service password")
    void sealRoundTrip() {
        String sealed = store.seal("svc-secret");
        assertNotEquals("svc-secret", sealed);
        assertEquals("svc-secret", store.open(sealed));
    }
}
