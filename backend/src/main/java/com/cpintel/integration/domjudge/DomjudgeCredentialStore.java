package com.cpintel.integration.domjudge;

import com.cpintel.common.SecretBox;
import com.cpintel.entity.ClassroomMember;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.ClassroomMemberRepository;
import com.cpintel.repository.jpa.ClassroomRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Holds the DOMjudge login a contestant competes under in each classroom, so the arena can act
 * as them.
 *
 * <p><b>This stores a password, and that is a real step down from the Codeforces path.</b>
 * {@link com.cpintel.practice.CfSessionStore} deliberately holds a session rather than a
 * password, because a leaked session expires and can be revoked by its owner from Codeforces'
 * own settings page, and a leaked password is none of those things. DOMjudge's API speaks HTTP
 * Basic and issues no session token an API client can hold instead, so submitting as a
 * contestant means replaying their password on every call.
 *
 * <p>What is done about it, given the requirement stands:
 *
 * <ul>
 *   <li><b>Sealed at rest</b> ({@link SecretBox}, AES-256-GCM) under a key that lives in the
 *       environment rather than in the database, so a database dump alone yields nothing.
 *   <li><b>On the enrolment, in Postgres.</b> A login lives exactly as long as the student is in
 *       the classroom, and is backed up with it. It used to sit in Redis with a 30-day expiry;
 *       that expiry was not renewed by use, so a class imported at the start of a semester lost
 *       its logins partway through it, and Redis was neither backed up nor safe from eviction.
 *   <li><b>Write-only across the API.</b> No endpoint returns the password, and neither does
 *       {@link Stored#toString()} — Lombok is deliberately not used here for that reason.
 * </ul>
 *
 * <p>The team is resolved once, when the credentials are provisioned, and kept alongside them.
 * That is what makes the arena's reads cheap: the judge attributes a submission to the team
 * behind the account, and knowing which team that is without asking again turns "whose
 * submissions are these" into a string comparison.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DomjudgeCredentialStore {

    /** Where logins lived before V18. Read only to move them across. */
    private static final String LEGACY_PREFIX = "dj:cred:";

    private final ClassroomMemberRepository members;
    private final ClassroomRepository classrooms;
    private final UserRepository users;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @Value("${cpintel.domjudge.credential-key:}")
    private String credentialKey;

    private volatile SecretBox box;

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

    /** Attaches a login, enrolling the student in the classroom if they were not already. */
    @Transactional
    public void save(Long classroomId, Long userId, Stored credentials) {
        requireKey();
        String sealed;
        try {
            sealed = box().seal(objectMapper.writeValueAsString(credentials));
        } catch (Exception e) {
            // The message deliberately carries no detail from the cause: a serialisation error
            // on this object could otherwise echo the field it failed on.
            throw new IllegalStateException("Could not persist DOMjudge credentials");
        }
        ClassroomMember member = members.findByClassroomIdAndUserUserId(classroomId, userId)
            .orElseGet(() -> ClassroomMember.builder()
                .classroomId(classroomId)
                .user(users.getReferenceById(userId))
                .build());
        member.setDomjudgeLogin(sealed);
        member.setDomjudgeUsername(credentials.username());
        member.setDomjudgeAttachedAt(Instant.now());
        members.save(member);
        log.info("Stored DOMjudge credentials for user {} in classroom {} (dj user={}, team={})",
            userId, classroomId, credentials.username(), credentials.teamId());
    }

    public Stored find(Long classroomId, Long userId) {
        if (!isConfigured() || classroomId == null || userId == null) return null;
        String sealed = members.findByClassroomIdAndUserUserId(classroomId, userId)
            .map(ClassroomMember::getDomjudgeLogin).orElse(null);
        if (sealed == null) return null;
        try {
            return objectMapper.readValue(box().open(sealed), Stored.class);
        } catch (Exception e) {
            // A changed key makes every stored login unreadable at once. That reads as "not
            // linked", which is recoverable by re-attaching, rather than as a server error.
            log.warn("Could not read DOMjudge credentials for user {} in classroom {}: {}",
                userId, classroomId, e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * The credentials, or a message aimed at the person who can actually fix it.
     *
     * A contestant cannot provision their own — an admin attaches them — so the message says
     * who to ask rather than offering a settings page that does not exist for them.
     */
    public Stored require(Long classroomId, Long userId) {
        Stored credentials = find(classroomId, userId);
        if (credentials == null) {
            throw ApiException.forbidden(
                "No DOMjudge account is attached to your CPIntel account for this classroom, so "
                    + "there is nothing to compete as. Ask the admin running it to attach one.");
        }
        return credentials;
    }

    /** Whether a login is attached, without decrypting it — for listings. */
    public boolean exists(Long classroomId, Long userId) {
        return members.findByClassroomIdAndUserUserId(classroomId, userId)
            .map(m -> m.getDomjudgeLogin() != null).orElse(false);
    }

    /** Detaches the login and keeps the enrolment. */
    @Transactional
    public void delete(Long classroomId, Long userId) {
        members.findByClassroomIdAndUserUserId(classroomId, userId).ifPresent(member -> {
            member.setDomjudgeLogin(null);
            member.setDomjudgeUsername(null);
            member.setDomjudgeAttachedAt(null);
            members.save(member);
        });
    }

    /**
     * Moves logins still in Redis into Postgres: those from before classrooms
     * ({@code dj:cred:<user>}, into {@code fallbackClassroomId}) and those from before V18
     * ({@code dj:cred:<classroom>:<user>}).
     *
     * <p>Run at startup by {@code ClassroomBootstrap}. A moved key is deleted, so running it
     * again does nothing. A login already attached in Postgres wins over the Redis copy.
     *
     * @return how many logins were moved
     */
    public int adoptFromRedis(Long fallbackClassroomId) {
        if (!isConfigured()) return 0;
        int moved = 0;
        ScanOptions options = ScanOptions.scanOptions().match(LEGACY_PREFIX + "*").count(500).build();
        java.util.List<String> keys = new java.util.ArrayList<>();
        try (var cursor = redis.scan(options)) {
            while (cursor.hasNext()) keys.add(cursor.next());
        }
        for (String key : keys) {
            String[] parts = key.substring(LEGACY_PREFIX.length()).split(":");
            try {
                Long classroomId = parts.length == 2 ? Long.valueOf(parts[0]) : fallbackClassroomId;
                Long userId = Long.valueOf(parts[parts.length - 1]);
                String blob = redis.opsForValue().get(key);
                if (classroomId != null && blob != null && classrooms.existsById(classroomId)
                        && users.existsById(userId) && !exists(classroomId, userId)) {
                    save(classroomId, userId, objectMapper.readValue(box().open(blob), Stored.class));
                    moved++;
                }
                redis.delete(key);
            } catch (NumberFormatException e) {
                // Not one of ours.
            } catch (Exception e) {
                log.warn("Could not move DOMjudge login {} out of Redis: {}",
                    key, e.getClass().getSimpleName());
            }
        }
        if (moved > 0) log.info("Moved {} DOMjudge login(s) from Redis into Postgres", moved);
        return moved;
    }

    // ------------------------------------------------------------- secrets

    /**
     * Seals a classroom's service-account password under the same key as the logins.
     */
    public String seal(String plain) {
        requireKey();
        return box().seal(plain);
    }

    /** The reverse of {@link #seal}, or null when the key cannot open it (e.g. after rotation). */
    public String open(String sealed) {
        if (sealed == null || !isConfigured()) return null;
        try {
            return box().open(sealed);
        } catch (Exception e) {
            log.warn("Could not decrypt a DOMjudge secret: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private SecretBox box() {
        SecretBox current = box;
        if (current == null) {
            requireKey();
            current = new SecretBox(credentialKey);
            box = current;
        }
        return current;
    }

    private void requireKey() {
        if (!isConfigured()) {
            throw ApiException.badRequest(
                "CPINTEL_DOMJUDGE_CREDENTIAL_KEY is not set, so DOMjudge credentials cannot be "
                    + "stored. Set it and restart before attaching accounts.");
        }
    }
}
