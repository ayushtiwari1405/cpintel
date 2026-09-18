package com.cpintel.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Retiring the access tokens a user is already holding.
 *
 * This is the half of a revocation that has to be right for "deactivate this account" to mean
 * anything before the token in their browser expires on its own. The two cases that matter are
 * the boundary — a token issued around the same moment as the revocation — and an unreachable
 * Redis, where the wrong answer would sign out the entire deployment at once.
 */
class JwtRevocationTest {

    private static final long ACCESS_TOKEN_MS = 900_000L;
    private static final Long USER_ID = 7L;

    private RedisTemplate<String, Object> redis;
    private ValueOperations<String, Object> values;
    private JwtService jwt;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(RedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);

        JwtProperties props = new JwtProperties();
        props.setSecret("test_secret_key_long_enough_for_hmac_sha256_signing_ok");
        props.setExpiryMs(ACCESS_TOKEN_MS);
        jwt = new JwtService(props, redis);
    }

    private void revokedAt(long epochMillis) {
        when(values.get("jwt:user-revoked:" + USER_ID)).thenReturn(String.valueOf(epochMillis));
    }

    @Test
    @DisplayName("with no revocation recorded, every token stands")
    void noMarkerMeansValid() {
        when(values.get(anyString())).thenReturn(null);

        assertFalse(jwt.isRevokedForUser(USER_ID, new Date()));
    }

    @Test
    @DisplayName("a token issued before the revocation is rejected")
    void olderTokenIsRevoked() {
        long now = System.currentTimeMillis();
        revokedAt(now);

        assertTrue(jwt.isRevokedForUser(USER_ID, new Date(now - 60_000)));
    }

    @Test
    @DisplayName("a token issued after the revocation still works, so signing back in is possible")
    void newerTokenSurvives() {
        long now = System.currentTimeMillis();
        revokedAt(now);

        assertFalse(jwt.isRevokedForUser(USER_ID, new Date(now + 5_000)));
    }

    @Test
    @DisplayName("a token issued in the same second as the revocation is kept, not killed")
    void sameSecondIsKept() {
        long now = System.currentTimeMillis();
        revokedAt(now);

        // A JWT records its issue time to the second, so a token minted just after a
        // revocation can look older than it is. Erring this way costs under a second of
        // enforcement; erring the other way would be a login that hands out dead tokens.
        assertFalse(jwt.isRevokedForUser(USER_ID, new Date(now - 400)));
    }

    @Test
    @DisplayName("an unreachable Redis leaves tokens alone rather than signing out everyone")
    void redisFailureFailsOpen() {
        when(values.get(anyString())).thenThrow(new RuntimeException("connection refused"));

        assertFalse(jwt.isRevokedForUser(USER_ID, new Date(0)));
    }

    @Test
    @DisplayName("a revocation that cannot be written is logged, not thrown at the admin")
    void revokeSurvivesRedisFailure() {
        doThrow(new RuntimeException("connection refused"))
            .when(values).set(anyString(), any(), any(java.time.Duration.class));

        // The durable half of a revocation is the refresh token row in Postgres, which the
        // caller has already written by this point; losing the cache marker must not undo it.
        assertDoesNotThrow(() -> jwt.revokeUserTokens(USER_ID));
    }

    @Test
    @DisplayName("the marker expires with the tokens it invalidates")
    void markerIsShortLived() {
        jwt.revokeUserTokens(USER_ID);

        verify(values).set(
            eq("jwt:user-revoked:" + USER_ID),
            anyString(),
            argThat((java.time.Duration ttl) -> ttl.toMillis() >= ACCESS_TOKEN_MS
                && ttl.toMillis() <= ACCESS_TOKEN_MS + 5_000));
    }

    @Test
    @DisplayName("a null issue time is not treated as revoked")
    void nullIssuedAt() {
        revokedAt(System.currentTimeMillis());

        assertFalse(jwt.isRevokedForUser(USER_ID, null));
    }
}
