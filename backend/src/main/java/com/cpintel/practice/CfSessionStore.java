package com.cpintel.practice;

import com.cpintel.exception.ApiException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds the Codeforces session a user handed us, so we can submit as them.
 *
 * This deliberately stores a *session*, not a password. A leaked session cookie is bad, but
 * it is bounded: it expires, the user can kill it from Codeforces' own settings page, and it
 * does not hand over the account itself. A leaked password is none of those things.
 *
 * At rest the cookie blob is AES-256-GCM encrypted under a key derived from
 * cpintel.practice.session-key, kept in Redis only (never Postgres or Mongo), with a TTL. The
 * cookie value is never returned by any endpoint — the status endpoint reports only the
 * handle and the expiry.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CfSessionStore {

    private static final String KEY_PREFIX = "cf:session:";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LENGTH = 12;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    private final SecureRandom random = new SecureRandom();

    @Value("${cpintel.practice.session-key}")
    private String sessionKey;

    @Value("${cpintel.practice.session-ttl-days:14}")
    private long sessionTtlDays;

    /**
     * A stored Codeforces session.
     *
     * <p>{@code userAgent} is the User-Agent of the browser the cookies were taken from, and it
     * is not optional decoration. Codeforces sits behind Cloudflare, and the {@code cf_clearance}
     * cookie that records "this client passed the challenge" is bound to the exact User-Agent
     * that earned it. Replaying the cookie under a different one is rejected, so every request
     * made with this session has to present the same string the browser did.
     *
     * <p>Null for sessions stored before this was carried; callers fall back to a default UA,
     * which is what those sessions were already using.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StoredSession(String handle, String cookieHeader, String userAgent,
                                Instant linkedAt, Instant expiresAt) {}

    public void save(Long userId, String handle, String cookieHeader, String userAgent) {
        Instant now = Instant.now();
        Instant expiry = now.plus(Duration.ofDays(sessionTtlDays));
        StoredSession session = new StoredSession(handle, cookieHeader, userAgent, now, expiry);
        try {
            String json = objectMapper.writeValueAsString(session);
            redis.opsForValue().set(KEY_PREFIX + userId, encrypt(json),
                Duration.ofDays(sessionTtlDays));
        } catch (Exception e) {
            throw new IllegalStateException("Could not persist Codeforces session", e);
        }
        log.info("Stored Codeforces session for user {} (handle={})", userId, handle);
    }

    public StoredSession find(Long userId) {
        String blob = redis.opsForValue().get(KEY_PREFIX + userId);
        if (blob == null) return null;
        try {
            return objectMapper.readValue(decrypt(blob), StoredSession.class);
        } catch (Exception e) {
            log.warn("Could not read CF session for user {}: {}", userId, e.getMessage());
            return null;
        }
    }

    public StoredSession require(Long userId) {
        StoredSession session = find(userId);
        if (session == null) {
            throw ApiException.badRequest(
                "No Codeforces session on file. Connect your account from the practice page.");
        }
        return session;
    }

    public void delete(Long userId) {
        redis.delete(KEY_PREFIX + userId);
    }

    /**
     * Strips a pasted Cookie header down to what should be kept.
     *
     * This used to be an allowlist of the cookies we believed mattered — JSESSIONID, X-User-Sha1,
     * 39ce7, RCPC and friends. That was wrong, and quietly so. Codeforces guards its heavier
     * pages (submission source among them) with a JavaScript challenge whose answer lands in a
     * {@code pow} cookie alongside a token under a randomised hex name like {@code 70a7c28f3de}.
     * Neither was on the list, so every stored session was missing the proof that the challenge
     * had been passed, and every request for a submission page came back as the interstitial
     * rather than the page. A name nobody can predict cannot be allowlisted.
     *
     * So: drop what is demonstrably not Codeforces' — third-party analytics — and keep the rest.
     * The header is copied from a codeforces.com request, and a Cookie header carries no host
     * information anyway, so an allowlist never really bounded what we held; it only bounded
     * what worked.
     */
    public static String sanitiseCookieHeader(String raw) {
        Map<String, String> keep = new LinkedHashMap<>();
        for (String part : raw.split(";")) {
            String chunk = part.trim();
            int eq = chunk.indexOf('=');
            if (eq <= 0) continue;
            String name = chunk.substring(0, eq).trim();
            String value = chunk.substring(eq + 1).trim();
            if (value.isEmpty()) continue;
            if (!isNoise(name)) keep.put(name, value);
        }
        // JSESSIONID is what makes it a session; without it there is nothing to store, and
        // saying so here beats failing later at submit time.
        boolean hasSession = keep.keySet().stream().anyMatch(n -> n.equalsIgnoreCase("JSESSIONID"));
        if (!hasSession) {
            throw ApiException.badRequest(
                "That cookie header has no Codeforces session cookie (JSESSIONID) in it. "
                    + "Make sure you copied it from a codeforces.com request while logged in.");
        }
        StringBuilder sb = new StringBuilder();
        keep.forEach((k, v) -> {
            if (sb.length() > 0) sb.append("; ");
            sb.append(k).append('=').append(v);
        });
        return sb.toString();
    }

    /** Third-party analytics: never needed, and the only thing worth actively discarding. */
    private static boolean isNoise(String name) {
        String n = name.toLowerCase();
        return n.equals("_ga") || n.startsWith("_ga_") || n.equals("_gid")
            || n.startsWith("_gat") || n.equals("_fbp") || n.startsWith("__utm")
            || n.startsWith("_hj") || n.equals("_clck") || n.equals("_clsk");
    }

    // ---------------------------------------------------------------- crypto

    private SecretKey key() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(sessionKey.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot derive session key", e);
        }
    }

    private String encrypt(String plain) throws Exception {
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return Base64.getEncoder().encodeToString(out);
    }

    private String decrypt(String blob) throws Exception {
        byte[] raw = Base64.getDecoder().decode(blob);
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(raw, 0, iv, 0, IV_LENGTH);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(raw, IV_LENGTH, raw.length - IV_LENGTH),
            StandardCharsets.UTF_8);
    }
}
