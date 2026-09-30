package com.cpintel.integration.domjudge;

import com.cpintel.entity.Classroom;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.ClassroomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The DOMjudge instances this deployment talks to: one per classroom.
 *
 * <p>Clients are built on first use and kept, because each carries state worth keeping — a
 * signed-in web session, the statement and sample routes it has discovered — and rebuilding
 * that per request would re-probe the judge on every statement. A client is rebuilt when the
 * classroom's judge settings change: {@link #evict} is called on every write, and the settings
 * are re-read after {@link #RECHECK} anyway so a change made by another backend instance is
 * picked up without a restart.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DomjudgeJudges {

    static final Duration RECHECK = Duration.ofMinutes(1);

    private final WebClient.Builder builder;
    private final ClassroomRepository classrooms;
    private final DomjudgeCredentialStore credentials;

    private final Map<Long, Held> clients = new ConcurrentHashMap<>();

    private record Held(DomjudgeClient client, String fingerprint, Instant checkedAt) {}

    /** The judge for one classroom. Throws when the classroom does not exist. */
    public DomjudgeClient forClassroom(Long classroomId) {
        if (classroomId == null) {
            throw ApiException.badRequest("Pick the classroom this contest runs in.");
        }
        Held held = clients.get(classroomId);
        if (held != null && Instant.now().isBefore(held.checkedAt().plus(RECHECK))) {
            return held.client();
        }

        Classroom classroom = classrooms.findById(classroomId)
            .orElseThrow(() -> ApiException.notFound("No classroom with id " + classroomId + "."));
        String fingerprint = fingerprint(classroom);

        if (held != null && held.fingerprint().equals(fingerprint)) {
            clients.put(classroomId, new Held(held.client(), fingerprint, Instant.now()));
            return held.client();
        }

        DomjudgeClient built = new DomjudgeClient(builder, classroomId,
            classroom.getDomjudgeUrl(), classroom.getServiceUsername(),
            credentials.open(classroom.getServicePassword()));
        clients.put(classroomId, new Held(built, fingerprint, Instant.now()));
        if (held != null) log.info("Classroom {} changed its judge settings; reconnecting", classroomId);
        return built;
    }

    /** The judge a classroom-qualified contest id lives on. */
    public DomjudgeClient forContest(String ref) {
        return forClassroom(JudgeContestRef.parse(ref).classroomId());
    }

    /**
     * Checks judge settings before they are saved, with a client that is thrown away after.
     *
     * @return the judge's version, when it reports one
     */
    public String check(String baseUrl, String username, String password) {
        return new DomjudgeClient(builder, 0, baseUrl, username, password).ping();
    }

    /** Forget a classroom's client, so the next use reads its settings afresh. */
    public void evict(Long classroomId) {
        if (classroomId != null) clients.remove(classroomId);
    }

    private static String fingerprint(Classroom classroom) {
        return Objects.toString(classroom.getDomjudgeUrl(), "") + "\u0000"
            + Objects.toString(classroom.getServiceUsername(), "") + "\u0000"
            + Objects.toString(classroom.getServicePassword(), "");
    }
}
