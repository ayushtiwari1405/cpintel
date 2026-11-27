package com.cpintel.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Base64;
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
     * For a token that records only the second it was issued in — one from before
     * {@link #ISSUED_MS_CLAIM} — those issued within this long of a revocation are left alone.
     *
     * A JWT records its issue time to the second, so a token minted in the same second as a
     * revocation cannot be told apart from one minted just before it. Erring towards keeping
     * the newer token matters because the alternative is a login loop: sign in, get a token
     * that is already considered dead, refresh, get another one. The window a revocation can
     * miss is under a second, and the refresh tokens are revoked in the same breath, so the
     * account still cannot mint anything new.
     */
    private static final long REVOCATION_GRACE_MS = 1000L;

    /** When the token was issued, to the millisecond; the standard claim keeps only seconds. */
    private static final String ISSUED_MS_CLAIM = "iat_ms";

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
        long now = System.currentTimeMillis();
        return Jwts.builder()
            .id(UUID.randomUUID().toString())
            .subject(String.valueOf(userId))
            .claims(claims)
            .claim(ISSUED_MS_CLAIM, now)
            .issuedAt(new Date(now))
            .expiration(new Date(now + props.getExpiryMs()))
            .signWith(signingKey(), Jwts.SIG.HS256)
            .compact();
    }

    /** The claim naming the examination an examination session is for. */
    public static final String EXAM_CLAIM = "exam";

    public String generateRefreshToken() {
        return UUID.randomUUID().toString() + "-" + UUID.randomUUID().toString();
    }

    /**
     * The refresh token that replaces this one at renewal: an HMAC of it under the server's key.
     *
     * <p>Derived rather than random so that it can be given again to a client that lost the
     * reply, without storing it. Without the key it cannot be worked out from the token it
     * replaces. The prefix keeps this use of the key apart from signing access tokens.
     */
    public String successorOf(String refreshToken) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey().getEncoded(), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(
                ("refresh-successor:" + refreshToken).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
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

    /**
     * Whether this token was signed out.
     *
     * Answers false when Redis cannot be read, the same direction {@link #isRevokedForUser} and
     * the rate limiter take. Letting the exception out instead failed every authenticated request
     * of the deployment for as long as Redis was down, because {@link JwtAuthFilter} asks this
     * before anything else. What is given up is bounded: a token its holder signed out of stays
     * usable until it expires on its own, at most one access-token lifetime, and the refresh
     * tokens revoked in Postgres at the same sign-out still cannot mint another.
     */
    private boolean isBlacklisted(String jti) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + jti));
        } catch (Exception e) {
            log.debug("Could not read the sign-out list for token {}: {}", jti, e.getMessage());
            return false;
        }
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
        if (issuedAt == null) return false;
        return revokedSince(userId, issuedAt.getTime() + REVOCATION_GRACE_MS - 1);
    }

    /** As above, to the millisecond where the token says when it was issued. */
    public boolean isSessionRevoked(Long userId, Claims claims) {
        Object issued = claims.get(ISSUED_MS_CLAIM);
        if (issued instanceof Number at) return revokedSince(userId, at.longValue());
        return isRevokedForUser(userId, claims.getIssuedAt());
    }

    /** Whether the user's sessions were revoked after this instant. */
    private boolean revokedSince(Long userId, long instant) {
        if (userId == null) return false;
        try {
            Object marker = redisTemplate.opsForValue().get(USER_REVOKE_PREFIX + userId);
            if (marker == null) return false;
            return instant < Long.parseLong(marker.toString());
        } catch (Exception e) {
            log.debug("Could not read revocation marker for user {}: {}", userId, e.getMessage());
            return false;
        }
    }
}
