package com.cpintel.integration.cses;

import com.cpintel.config.AppMetrics;
import com.cpintel.integration.OutboundRateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * cses.fi: its official API (the one the University of Helsinki's own command-line client
 * uses, under /api) and, for statements, the public task pages.
 *
 * <p>The API signs a user in without a password passing through CPIntel: {@link #startLogin}
 * returns a token and a cses.fi address, the user signs in there to approve the token, and the
 * token then acts for that user — reading their task statuses and submissions and submitting.
 * Until it is approved, CSES answers the token as invalid.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CsesClient {

    private static final String PLATFORM = "cses";
    private static final long MAX_WAIT_MS = 30_000;
    /** The problem set is a "course" to the API. */
    public static final String COURSE = "problemset";

    private final WebClient platformWebClient;
    private final OutboundRateLimiter rateLimiter;
    private final AppMetrics metrics;

    @Value("${cpintel.platforms.cses.base-url:https://cses.fi}")
    private String baseUrl;

    @Value("${cpintel.platforms.cses.rate-limit-ms:500}")
    private long rateLimitMs;

    private WebClient client;

    @PostConstruct
    void init() {
        client = platformWebClient.mutate().baseUrl(baseUrl).build();
    }

    /** CSES refused the token: never approved, expired, or signed out on cses.fi. */
    public static class TokenRejected extends RuntimeException {
        public TokenRejected() { super("CSES no longer accepts this sign-in."); }
    }

    public record Login(String token, String authenticationUrl) {}

    public record User(long id, String username, String displayName) {}

    public record Submission(long id, Instant time, String outcome) {}

    /** One submission as CSES grades it. {@code result} is set once {@code pending} is false. */
    public record Graded(long id, String taskId, Instant time, boolean pending, String status, String result,
                         Integer finishedTests, Integer totalTests, String compiler, List<TestVerdict> tests) {}

    public record TestVerdict(int number, String verdict, Integer timeMs) {}

    public record Language(String name, String option) {}

    // ---------------------------------------------------------------- sign-in

    public Login startLogin() {
        JsonNode r = call("login", () -> json(client.post().uri("/api/login").retrieve()
            .bodyToMono(String.class).block(Duration.ofSeconds(20))));
        return new Login(r.path("X-Auth-Token").asText(), r.path("authentication_url").asText());
    }

    /** Who the token is for, or null while it has not been approved (or is no good). */
    public User whoAmI(String token) {
        try {
            JsonNode r = authed("whoami", token, c -> c.get().uri("/api/login"));
            return new User(r.path("id").asLong(), r.path("username").asText(),
                r.path("displayname").asText(null));
        } catch (TokenRejected e) {
            return null;
        }
    }

    public void logout(String token) {
        try {
            authed("logout", token, c -> c.post().uri("/api/logout"));
        } catch (Exception e) {
            log.debug("CSES logout failed: {}", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- reading

    /** Every problem-set task's status for this user: "pass", "fail" or "none". */
    public Map<String, String> statuses(String token) {
        JsonNode r = authed("list", token, c -> c.get().uri("/api/courses/{c}/list", COURSE));
        Map<String, String> out = new HashMap<>();
        forEachTask(r, t -> out.put(t.path("id").asText(), t.path("status").asText("none")));
        return out;
    }

    /** This user's submissions to one task. */
    public List<Submission> submissions(String token, String taskId) {
        JsonNode r = authed("submissions", token,
            c -> c.get().uri("/api/courses/{c}/submissions?task={t}", COURSE, taskId));
        List<Submission> out = new ArrayList<>();
        for (JsonNode s : r.path("submissions")) {
            Instant at = CsesTime.parse(s.path("time").asText(null));
            if (at != null) out.add(new Submission(s.path("id").asLong(), at, s.path("outcome_status").asText("none")));
        }
        return out;
    }

    private volatile Map<String, String> taskNames;
    private volatile Instant taskNamesAt;

    /** The problem set's task names by id, read without signing in. Kept a day. */
    public Map<String, String> taskNames() {
        Map<String, String> known = taskNames;
        if (known != null && taskNamesAt.plus(Duration.ofDays(1)).isAfter(Instant.now())) return known;
        JsonNode r = call("tasks", () -> json(client.get().uri("/api/courses/{c}/list", COURSE).retrieve()
            .bodyToMono(String.class).block(Duration.ofSeconds(20))));
        Map<String, String> names = new LinkedHashMap<>();
        forEachTask(r, t -> names.put(t.path("id").asText(), t.path("name").asText()));
        taskNames = names;
        taskNamesAt = Instant.now();
        return names;
    }

    private static void forEachTask(JsonNode list, java.util.function.Consumer<JsonNode> each) {
        for (JsonNode section : list.path("sections")) {
            for (JsonNode item : section.path("list")) {
                if ("task".equals(item.path("item_type").asText())) each.accept(item);
            }
        }
    }

    // ---------------------------------------------------------------- submitting

    public Graded submit(String token, String taskId, Language language, String filename, String source) {
        Map<String, Object> lang = new HashMap<>();
        lang.put("name", language.name());
        lang.put("option", language.option());
        Map<String, Object> body = Map.of("language", lang, "filename", filename,
            "content", Base64.getEncoder().encodeToString(source.getBytes(StandardCharsets.UTF_8)));
        JsonNode r = authed("submit", token, c -> c.post()
            .uri("/api/courses/{c}/submissions?task={t}", COURSE, taskId)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body));
        return graded(r);
    }

    public Graded submission(String token, long submissionId) {
        return graded(authed("submission", token,
            c -> c.get().uri("/api/courses/{c}/submissions/{s}", COURSE, submissionId)));
    }

    static Graded graded(JsonNode r) {
        List<TestVerdict> tests = new ArrayList<>();
        for (JsonNode t : r.path("tests")) {
            tests.add(new TestVerdict(t.path("number").asInt(), t.path("verdict").asText(),
                t.path("time").isNumber() ? t.path("time").asInt() : null));
        }
        JsonNode progress = r.path("test_progress");
        return new Graded(r.path("id").asLong(), r.path("task").path("id").asText(null),
            CsesTime.parse(r.path("time").asText(null)), r.path("pending").asBoolean(false),
            r.path("status").asText(null), r.path("result").asText(null),
            progress.path("finished_tests").isNumber() ? progress.path("finished_tests").asInt() : null,
            progress.path("total_tests").isNumber() ? progress.path("total_tests").asInt() : null,
            r.path("compiler").asText(null), tests);
    }

    // ---------------------------------------------------------------- plumbing

    private interface Request {
        WebClient.RequestHeadersSpec<?> build(WebClient c);
    }

    private JsonNode authed(String operation, String token, Request request) {
        return call(operation, () -> {
            try {
                return json(request.build(client).header("X-Auth-Token", token).retrieve()
                    .bodyToMono(String.class).block(Duration.ofSeconds(30)));
            } catch (WebClientResponseException e) {
                if (e.getStatusCode().value() == 401) throw new TokenRejected();
                // CSES explains a refused submission in the body ({message, code}).
                String message = e.getResponseBodyAsString();
                throw new IllegalStateException(CsesTime.messageOf(message, "CSES answered " + e.getStatusCode().value()));
            }
        });
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * The API's JSON. CSES labels it text/html, so it is read as text and parsed here rather
     * than left to the client's content negotiation, which refuses it. An empty body (a 204
     * from logout) is an empty object.
     */
    static JsonNode json(String body) {
        if (body == null || body.isBlank()) return JSON.createObjectNode();
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("CSES answered with something that is not JSON");
        }
    }

    private JsonNode call(String operation, Supplier<JsonNode> body) {
        long started = System.nanoTime();
        boolean ok = false;
        try {
            rateLimiter.acquire(PLATFORM, rateLimitMs, MAX_WAIT_MS);
            JsonNode out = body.get();
            ok = true;
            return out;
        } finally {
            metrics.platformCall(PLATFORM, operation, ok, Duration.ofNanos(System.nanoTime() - started));
        }
    }
}
