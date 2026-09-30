package com.cpintel.integration.domjudge;

import com.cpintel.common.SecretBox;
import com.cpintel.entity.ClassroomMember;
import com.cpintel.entity.User;
import com.cpintel.repository.jpa.ClassroomMemberRepository;
import com.cpintel.repository.jpa.ClassroomRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A student in two classrooms holds two judge logins, neither replacing the other, and each
 * lasts as long as the enrolment it sits on.
 */
class DomjudgeCredentialStoreTest {

    private static final Long USER = 42L;
    private static final String KEY = "test-key";

    /** classroom -> user -> row, standing in for classroom_members. */
    private final Map<String, ClassroomMember> rows = new HashMap<>();
    private final Map<String, String> redisStore = new HashMap<>();
    private DomjudgeCredentialStore store;

    private static String k(Long classroomId, Long userId) {
        return classroomId + "/" + userId;
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ClassroomMemberRepository members = mock(ClassroomMemberRepository.class);
        when(members.findByClassroomIdAndUserUserId(any(), any())).thenAnswer(c ->
            Optional.ofNullable(rows.get(k(c.getArgument(0), c.getArgument(1)))));
        when(members.save(any())).thenAnswer(c -> {
            ClassroomMember m = c.getArgument(0);
            rows.put(k(m.getClassroomId(), m.getUser().getUserId()), m);
            return m;
        });
        UserRepository users = mock(UserRepository.class);
        when(users.getReferenceById(any())).thenAnswer(c ->
            User.builder().userId(c.getArgument(0)).build());
        when(users.existsById(any())).thenReturn(true);
        ClassroomRepository classrooms = mock(ClassroomRepository.class);
        when(classrooms.existsById(any())).thenReturn(true);

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(c -> redisStore.get(c.<String>getArgument(0)));
        when(redis.delete(anyString())).thenAnswer(c ->
            redisStore.remove(c.<String>getArgument(0)) != null);
        when(redis.scan(any(ScanOptions.class))).thenAnswer(c -> cursorOver(
            new ArrayList<>(redisStore.keySet())));

        store = new DomjudgeCredentialStore(members, classrooms, users, redis);
        ReflectionTestUtils.setField(store, "credentialKey", KEY);
    }

    @SuppressWarnings("unchecked")
    private static Cursor<String> cursorOver(List<String> keys) {
        Cursor<String> cursor = mock(Cursor.class);
        var it = keys.iterator();
        when(cursor.hasNext()).thenAnswer(c -> it.hasNext());
        when(cursor.next()).thenAnswer(c -> it.next());
        return cursor;
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
    @DisplayName("attaching enrols, and detaching keeps the enrolment")
    void detachKeepsEnrolment() {
        store.save(1L, USER, login("asha", "t1"));
        assertTrue(store.exists(1L, USER));
        assertEquals("asha", rows.get(k(1L, USER)).getDomjudgeUsername());

        store.delete(1L, USER);

        assertFalse(store.exists(1L, USER));
        assertNotNull(rows.get(k(1L, USER)), "the student is still in the classroom");
    }

    @Test
    @DisplayName("what is stored is sealed, marked with its key version, and not the password")
    void sealedAtRest() {
        store.save(1L, USER, login("asha", "t1"));
        String sealed = rows.get(k(1L, USER)).getDomjudgeLogin();
        assertTrue(sealed.startsWith("v1:"));
        assertFalse(sealed.contains("pw-asha"));
    }

    @Test
    @DisplayName("logins still in Redis move into Postgres, from both old key shapes")
    void adoptsFromRedis() throws Exception {
        SecretBox box = new SecretBox(KEY);
        ObjectMapper json = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        redisStore.put("dj:cred:42", box.seal(json.writeValueAsString(login("old", "t0"))));
        redisStore.put("dj:cred:5:43", box.seal(json.writeValueAsString(login("per", "t5"))));

        assertEquals(2, store.adoptFromRedis(1L));

        assertEquals("t0", store.find(1L, 42L).teamId());
        assertEquals("t5", store.find(5L, 43L).teamId());
        assertTrue(redisStore.isEmpty(), "moved keys are removed from Redis");
        assertEquals(0, store.adoptFromRedis(1L), "running again does nothing");
    }
}
