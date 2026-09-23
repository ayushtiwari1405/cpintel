package com.cpintel.integration.domjudge;

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

/**
 * Holds the DOMjudge login a contestant competes under, so the arena can act as them.
 *
 * <p><b>This stores a password, and that is a real step down from the Codeforces path.</b>
 * {@link com.cpintel.practice.CfSessionStore} deliberately holds a session rather than a
 * password, because a leaked session expires and can be revoked by its owner from Codeforces'
 * own settings page, and a leaked password is none of those things. DOMjudge's API speaks HTTP
 * Basic and issues no session token an API client can hold instead, so submitting as a
 * contestant means replaying their password on every call. There is no version of that which
 * is as safe as the Codeforces arrangement, and pretending otherwise in a comment would not
 * make it so.
 *
 * <p>What is done about it, given the requirement stands:
 *
 * <ul>
 *   <li><b>Redis only, never Postgres or Mongo.</b> The durable stores are backed up, replicated
 *       and dumped into places a password should not travel to. Redis here is a cache with a
 *       TTL, so the blast radius of a stale backup is bounded by that TTL.
 *   <li><b>AES-256-GCM at rest</b>, under a key that lives in the environment rather than in
 *       the database — so a dump of Redis alone yields nothing.
 *   <li><b>A TTL.</b> Credentials provisioned for a contest expire on their own. Somebody who
 *       forgets to clean up after a round is not left holding passwords indefinitely.
 *   <li><b>Write-only across the API.</b> No endpoint returns the password, and neither does
 *       {@link Stored#toString()} — Lombok is deliberately not used here for that reason.
 * </ul>
 *
 * <p>The team is resolved once, when the credentials are provisioned, and cached alongside
 * them. That is what makes the arena's reads cheap: the judge attributes a submission to the
 * team behind the account, and knowing which team that is without asking again turns "whose
 * submissions are these" into a string comparison.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DomjudgeCredentialStore {

    private static final String KEY_PREFIX = "dj:cred:";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LENGTH = 12;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    private final SecureRandom random = new SecureRandom();

    @Value("${cpintel.domjudge.credential-key:}")
    private String credentialKey;

    @Value("${cpintel.domjudge.credential-ttl-days:30}")
    private long credentialTtlDays;

    /**
     * One contestant's DOMjudge login, plus what it resolved to and what an admin decided.
     *
     * A record rather than a Lombok {@code @Data} class on purpose: {@code @Data} would
     * generate a {@code toString()} containing the password, and the first time this object
     * reached a log line or an exception message the password would go with it. The explicit
     * {@link #toString()} below is the whole point of writing this out by hand.
     *
     * <p><b>Two teams, deliberately not merged.</b> {@code teamId} is the judge's own answer —
     * the team DOMjudge will attribute this account's submissions to, which nothing in CPIntel
     * can change, because the judge derives it from the login rather than from anything sent
     * with the submission. {@code assignedTeamId} is the team an admin decided this person
     * belongs to, and it drives CPIntel's own grouping and standings.
     *
     * <p>Usually they agree, and when they do the distinction costs nothing. When they do not,
     * collapsing them into one field would have to pick a winner, and either choice is wrong:
     * using the assigned team for the submissions filter would hide a contestant's own
     * verdicts from them, and using the judge team for standings would quietly ignore what the
     * admin asked for. Keeping both lets the disagreement be reported instead of resolved
     * behind somebody's back.
     *
     * @param username         the DOMjudge account name
     * @param password         replayed on every call; never returned by any endpoint
     * @param name             the contestant's display name, as the admin entered it
     * @param teamId           the team DOMjudge attributes this account's submissions to
     * @param teamName         that team's name, for display
     * @param assignedTeamId   the team the admin put them in, or null to follow the judge
     * @param assignedTeamName that team's name, for display
     * @param provisioned      when an admin attached these credentials
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stored(String username, String password, String name,
                         String teamId, String teamName,
                         String assignedTeamId, String assignedTeamName,
                         Instant provisioned) {

        /** Never let the password reach a log, a stack trace or an error body. */
        @Override
        public String toString() {
            return "DomjudgeCredentials[username=" + username + ", teamId=" + teamId + "]";
        }

        /**
         * The team CPIntel groups this person under, which is the admin's if they said.
         *
         * Never used for attribution — see the class note. This is the answer to "who is this
         * person measured alongside", not "where does their code land".
         */
        public String effectiveTeamId() {
            return assignedTeamId != null ? assignedTeamId : teamId;
        }

        public String effectiveTeamName() {
            return assignedTeamId != null ? assignedTeamName : teamName;
        }

        /** True when the admin's choice disagrees with what the judge reports. */
        public boolean teamMismatch() {
            return assignedTeamId != null && !assignedTeamId.equals(teamId);
        }
    }

    /** True when an operator has set a key, without which nothing here can be stored. */
    public boolean isConfigured() {
        return credentialKey != null && !credentialKey.isBlank();
    }

    public void save(Long userId, Stored credentials) {
        requireKey();
        try {
            String json = objectMapper.writeValueAsString(credentials);
            redis.opsForValue().set(KEY_PREFIX + userId, encrypt(json),
                Duration.ofDays(credentialTtlDays));
        } catch (Exception e) {
            // The message deliberately carries no detail from the cause: a serialisation error
            // on this object could otherwise echo the field it failed on.
            throw new IllegalStateException("Could not persist DOMjudge credentials");
        }
        log.info("Stored DOMjudge credentials for user {} (dj user={}, team={})",
            userId, credentials.username(), credentials.teamId());
    }

    public Stored find(Long userId) {
        if (!isConfigured()) return null;
        String blob = redis.opsForValue().get(KEY_PREFIX + userId);
        if (blob == null) return null;
        try {
            return objectMapper.readValue(decrypt(blob), Stored.class);
        } catch (Exception e) {
            // A key rotation invalidates every stored blob at once. That reads as "not linked",
            // which is recoverable by re-provisioning, rather than as a server error.
            log.warn("Could not read DOMjudge credentials for user {}: {}",
                userId, e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * The credentials, or a message aimed at the person who can actually fix it.
     *
     * A contestant cannot provision their own — an admin attaches them — so the message says
     * who to ask rather than offering a settings page that does not exist for them.
     */
    public Stored require(Long userId) {
        Stored credentials = find(userId);
        if (credentials == null) {
            throw ApiException.forbidden(
                "No DOMjudge account is attached to your CPIntel account, so there is nothing "
                    + "to compete as. Ask the admin running this contest to attach one.");
        }
        return credentials;
    }

    public void delete(Long userId) {
        redis.delete(KEY_PREFIX + userId);
    }

    /** How long the stored credentials have left, for the admin screen. */
    public Duration timeToLive(Long userId) {
        Long seconds = redis.getExpire(KEY_PREFIX + userId);
        return seconds == null || seconds < 0 ? null : Duration.ofSeconds(seconds);
    }

    // ---------------------------------------------------------------- crypto

    private void requireKey() {
        if (!isConfigured()) {
            throw ApiException.badRequest(
                "CPINTEL_DOMJUDGE_CREDENTIAL_KEY is not set, so DOMjudge credentials cannot be "
                    + "stored. Set it and restart before attaching accounts.");
        }
    }

    private SecretKey key() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(credentialKey.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot derive DOMjudge credential key", e);
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
