package com.cpintel.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class JwtService {

    private final JwtProperties props;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";
    private static final String USER_REVOKE_PREFIX = "jwt:user-revoked:";

    /**
     * Tokens issued within this long of a revocation are left alone.
     *
     * A JWT records its issue time to the second, so a token minted in the same second as a
     * revocation cannot be told apart from one minted just before it. Erring towards keeping
     * the newer token matters because the alternative is a login loop: sign in, get a token
     * that is already considered dead, refresh, get another one. The window a revocation can
     * miss is under a second, and the refresh tokens are revoked in the same breath, so the
     * account still cannot mint anything new.
     */
    private static final long REVOCATION_GRACE_MS = 1000L;

    private SecretKey signingKey() {
        byte[] keyBytes = Decoders.BASE64.decode(
            java.util.Base64.getEncoder().encodeToString(props.getSecret().getBytes())
        );
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateAccessToken(Long userId, String email, String role) {
        return generateAccessToken(userId, email, role, null);
    }

    /**
     * @param examId the examination this session was signed in for, with the examination
     *               password; null for an ordinary session. See {@link SessionMode}.
     */
    public String generateAccessToken(Long userId, String email, String role, Long examId) {
        Map<String, Object> claims = new java.util.HashMap<>(Map.of("email", email, "role", role));
        if (examId != null) claims.put(EXAM_CLAIM, examId);
        return Jwts.builder()
            .id(UUID.randomUUID().toString())
            .subject(String.valueOf(userId))
            .claims(claims)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + props.getExpiryMs()))
            .signWith(signingKey(), Jwts.SIG.HS256)
            .compact();
    }

    /** The claim naming the examination an examination session is for. */
    public static final String EXAM_CLAIM = "exam";

    public String generateRefreshToken() {
        return UUID.randomUUID().toString() + "-" + UUID.randomUUID().toString();
    }

    public Claims extractClaims(String token) {
        return Jwts.parser()
            .verifyWith(signingKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    public Long extractUserId(String token) {
        return Long.parseLong(extractClaims(token).getSubject());
    }

    public boolean isValid(String token) {
        try {
            Claims claims = extractClaims(token);
            if (isBlacklisted(claims.getId())) return false;
            return !claims.getExpiration().before(new Date());
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid JWT: {}", e.getMessage());
            return false;
        }
    }

    public void blacklist(String token) {
        try {
            Claims claims = extractClaims(token);
            long ttl = claims.getExpiration().getTime() - System.currentTimeMillis();
            if (ttl > 0) {
                redisTemplate.opsForValue().set(
                    BLACKLIST_PREFIX + claims.getId(),
                    "1",
                    Duration.ofMillis(ttl)
                );
            }
        } catch (JwtException e) {
            log.debug("Could not blacklist token: {}", e.getMessage());
        }
    }

    private boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + jti));
    }

    /**
     * Retires every access token this user is currently holding.
     *
     * Revoking their refresh tokens stops them getting a new one, but an access token already
     * in a browser stays valid until it expires on its own — which is how a deactivated account
     * would keep working for another quarter of an hour. This closes that gap by remembering
     * the moment of revocation and rejecting anything older.
     *
     * The marker only has to outlive the tokens it invalidates: anything issued before it is
     * expired by then anyway, so it is given the access token lifetime and left to expire.
     */
    public void revokeUserTokens(Long userId) {
        try {
            redisTemplate.opsForValue().set(
                USER_REVOKE_PREFIX + userId,
                String.valueOf(System.currentTimeMillis()),
                Duration.ofMillis(props.getExpiryMs() + REVOCATION_GRACE_MS)
            );
        } catch (Exception e) {
            log.warn("Could not revoke access tokens for user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Whether this token predates a revocation of its user's sessions.
     *
     * Answers false when the marker cannot be read at all. That is a deliberate choice to keep
     * an unreachable Redis from signing out every user of the deployment at once; the durable
     * half of a revocation is the refresh token row in Postgres, which does not depend on this.
     */
    public boolean isRevokedForUser(Long userId, Date issuedAt) {
        if (userId == null || issuedAt == null) return false;
        try {
            Object marker = redisTemplate.opsForValue().get(USER_REVOKE_PREFIX + userId);
            if (marker == null) return false;
            long revokedAt = Long.parseLong(marker.toString());
            return issuedAt.getTime() + REVOCATION_GRACE_MS <= revokedAt;
        } catch (Exception e) {
            log.debug("Could not read revocation marker for user {}: {}", userId, e.getMessage());
            return false;
        }
    }
}
