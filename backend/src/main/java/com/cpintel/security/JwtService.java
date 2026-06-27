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

    private SecretKey signingKey() {
        byte[] keyBytes = Decoders.BASE64.decode(
            java.util.Base64.getEncoder().encodeToString(props.getSecret().getBytes())
        );
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateAccessToken(Long userId, String email, String role) {
        return Jwts.builder()
            .id(UUID.randomUUID().toString())
            .subject(String.valueOf(userId))
            .claims(Map.of("email", email, "role", role))
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + props.getExpiryMs()))
            .signWith(signingKey(), Jwts.SIG.HS256)
            .compact();
    }

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
}
