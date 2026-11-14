package com.cpintel.integration.leetcode;

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
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LeetCode's public GraphQL endpoint: what labs need and nothing more.
 *
 * <p>It answers anonymously for three things: whether a user exists (and their profile summary,
 * which is where a student proves a handle is theirs), what a problem is called, and a user's
 * most recent accepted submissions. That last list is short — LeetCode returns at most about
 * twenty — so labs poll it often enough that a student cannot outrun it between two looks.
 * There is no anonymous way to ask whether a user ever solved a given problem, which is why a
 * LeetCode solve is only ever seen as a recent one.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LeetCodeClient {

    private static final String PLATFORM = "leetcode";
    private static final long MAX_WAIT_MS = 30_000;
    /** The most LeetCode returns for recentAcSubmissionList. */
    public static final int RECENT_LIMIT = 20;

    private final WebClient platformWebClient;
    private final OutboundRateLimiter rateLimiter;
    private final AppMetrics metrics;

    @Value("${cpintel.platforms.leetcode.base-url:https://leetcode.com}")
    private String baseUrl;

    @Value("${cpintel.platforms.leetcode.rate-limit-ms:1000}")
    private long rateLimitMs;

    private WebClient client;

    @PostConstruct
    void init() {
        client = platformWebClient.mutate()
            .baseUrl(baseUrl)
            // LeetCode refuses GraphQL posts that do not look like they came from its own site.
            .defaultHeader("Referer", baseUrl + "/")
            .build();
    }

    public record Profile(String username, String aboutMe) {}

    public record Problem(String slug, String title, String difficulty, String frontendId,
                          boolean paidOnly) {}

    public record AcceptedSubmission(String id, String slug, Instant at) {}

    /** The user, or null when LeetCode has no such username. */
    public Profile profile(String username) {
        JsonNode data = query("profile", """
            query($u: String!) { matchedUser(username: $u) { username profile { aboutMe } } }
            """, Map.of("u", username));
        JsonNode user = data.path("matchedUser");
        if (user.isMissingNode() || user.isNull()) return null;
        return new Profile(user.path("username").asText(username),
            user.path("profile").path("aboutMe").asText(""));
    }

    /** Problems do not change name; kept so saving a lab does not look each one up again. */
    private final java.util.Map<String, Problem> problems = new java.util.concurrent.ConcurrentHashMap<>();

    /** The problem, or null when there is none with that slug. */
    public Problem problem(String slug) {
        Problem known = problems.get(slug);
        if (known != null) return known;
        Problem found = fetchProblem(slug);
        if (found != null && problems.size() < 5_000) problems.put(slug, found);
        return found;
    }

    private Problem fetchProblem(String slug) {
        JsonNode data = query("question", """
            query($s: String!) {
              question(titleSlug: $s) { title titleSlug difficulty questionFrontendId isPaidOnly }
            }
            """, Map.of("s", slug));
        JsonNode q = data.path("question");
        if (q.isMissingNode() || q.isNull()) return null;
        return new Problem(q.path("titleSlug").asText(slug), q.path("title").asText(slug),
            q.path("difficulty").asText(null), q.path("questionFrontendId").asText(null),
            q.path("isPaidOnly").asBoolean(false));
    }

    public record Snippet(String lang, String langSlug, String code) {}

    /** What solving a problem needs: its statement (LeetCode's HTML), starter code and examples. */
    public record Statement(String questionId, String frontendId, String slug, String title,
                            String difficulty, boolean paidOnly, String contentHtml,
                            List<Snippet> snippets, List<String> exampleTestcases) {}

    /** Statements change rarely; kept briefly so a class opening one problem costs one call. */
    private final java.util.Map<String, Cached> statements = new java.util.concurrent.ConcurrentHashMap<>();
    private record Cached(Statement statement, Instant at) {}
    private static final Duration STATEMENT_TTL = Duration.ofHours(6);

    /**
     * The problem's statement and starter code, or null when there is none with that slug. A
     * premium problem comes back with no content to an anonymous caller.
     */
    public Statement statement(String slug) {
        Cached hit = statements.get(slug);
        if (hit != null && hit.at().plus(STATEMENT_TTL).isAfter(Instant.now())) return hit.statement();
        JsonNode data = query("statement", """
            query($s: String!) {
              question(titleSlug: $s) {
                questionId questionFrontendId title titleSlug difficulty isPaidOnly content
                exampleTestcaseList codeSnippets { lang langSlug code }
              }
            }
            """, Map.of("s", slug));
        JsonNode q = data.path("question");
        if (q.isMissingNode() || q.isNull()) return null;
        List<Snippet> snippets = new ArrayList<>();
        for (JsonNode sn : q.path("codeSnippets")) {
            snippets.add(new Snippet(sn.path("lang").asText(), sn.path("langSlug").asText(), sn.path("code").asText("")));
        }
        List<String> examples = new ArrayList<>();
        for (JsonNode ex : q.path("exampleTestcaseList")) examples.add(ex.asText());
        Statement st = new Statement(q.path("questionId").asText(), q.path("questionFrontendId").asText(null),
            q.path("titleSlug").asText(slug), q.path("title").asText(slug), q.path("difficulty").asText(null),
            q.path("isPaidOnly").asBoolean(false),
            q.path("content").isNull() ? null : q.path("content").asText(null), snippets, examples);
        if (statements.size() < 2_000) statements.put(slug, new Cached(st, Instant.now()));
        return st;
    }

    /** The user's latest accepted submissions, newest first, at most {@link #RECENT_LIMIT}. */
    public List<AcceptedSubmission> recentAccepted(String username) {
        JsonNode data = query("recentAc", """
            query($u: String!, $n: Int!) {
              recentAcSubmissionList(username: $u, limit: $n) { id titleSlug timestamp }
            }
            """, Map.of("u", username, "n", RECENT_LIMIT));
        List<AcceptedSubmission> out = new ArrayList<>();
        for (JsonNode s : data.path("recentAcSubmissionList")) {
            long seconds = s.path("timestamp").asLong(0);
            if (seconds <= 0) continue;
            out.add(new AcceptedSubmission(s.path("id").asText(null), s.path("titleSlug").asText(),
                Instant.ofEpochSecond(seconds)));
        }
        return out;
    }

    private JsonNode query(String operation, String query, Map<String, Object> variables) {
        long started = System.nanoTime();
        boolean ok = false;
        try {
            rateLimiter.acquire(PLATFORM, rateLimitMs, MAX_WAIT_MS);
            JsonNode body = client.post()
                .uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", query, "variables", variables))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(2)).filter(LeetCodeClient::transientFailure))
                .block(Duration.ofSeconds(20));
            if (body == null) throw new IllegalStateException("LeetCode returned nothing");
            // An unknown user comes back as data.matchedUser = null with an error alongside it;
            // that is an answer, not a failure. Only a reply with no data at all is one.
            JsonNode data = body.path("data");
            if (data.isMissingNode() || data.isNull()) {
                throw new IllegalStateException("LeetCode answered " + operation + " with errors: "
                    + body.path("errors").toString());
            }
            ok = true;
            return data;
        } finally {
            metrics.platformCall(PLATFORM, operation, ok, Duration.ofNanos(System.nanoTime() - started));
        }
    }

    static boolean transientFailure(Throwable e) {
        if (e instanceof WebClientResponseException r) {
            return r.getStatusCode().is5xxServerError() || r.getStatusCode().value() == 429;
        }
        return e instanceof WebClientRequestException || e instanceof java.util.concurrent.TimeoutException;
    }
}
