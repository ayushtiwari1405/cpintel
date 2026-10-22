package com.cpintel.integration.codeforces;

import com.cpintel.config.AppMetrics;
import com.cpintel.exception.ApiException;
import com.cpintel.integration.OutboundRateLimiter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * What the Codeforces client does when Codeforces does not answer as hoped: a refused request,
 * a server error, a body that is not what it should be, an empty history.
 *
 * <p>Runs against a stand-in Codeforces on a local port (the JDK's own HTTP server), so nothing
 * here reaches the real site. Each test scripts the answers, in order, for one path.
 */
class CodeforcesClientFailureTest {

    private HttpServer server;
    private final Map<String, Deque<int[]>> statuses = new ConcurrentHashMap<>();
    private final Map<String, Deque<String>> bodies = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private AppMetrics metrics;
    private CodeforcesClient client;

    /** Queues one answer for a path (the part before the query string). */
    private void answer(String path, int status, String body) {
        statuses.computeIfAbsent(path, p -> new ArrayDeque<>()).add(new int[] { status });
        bodies.computeIfAbsent(path, p -> new ArrayDeque<>()).add(body);
    }

    private int callsTo(String path) {
        return calls.getOrDefault(path, new AtomicInteger()).get();
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            calls.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
            Deque<int[]> s = statuses.get(path);
            int status = s == null || s.isEmpty() ? 404 : s.size() > 1 ? s.poll()[0] : s.peek()[0];
            Deque<String> b = bodies.get(path);
            String body = b == null || b.isEmpty() ? "" : b.size() > 1 ? b.poll() : b.peek();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        metrics = mock(AppMetrics.class);
        client = new CodeforcesClient(WebClient.builder().build(), mock(OutboundRateLimiter.class), metrics);
        ReflectionTestUtils.setField(client, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(client, "rateLimitMs", 0L);
        client.init();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static final String NOT_FOUND = """
        {"status":"FAILED","comment":"handles: User with handle nobody_here not found"}""";

    @Test
    @DisplayName("an unknown handle is turned down at once, on one request, not after the retries")
    void unknownHandleIsNotRetried() {
        answer("/user.info", 400, NOT_FOUND);

        long started = System.nanoTime();
        boolean exists = client.handleExists("nobody_here");

        assertThat(exists).isFalse();
        assertThat(callsTo("/user.info")).isEqualTo(1);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("a refused submission list keeps its own message instead of a retry wrapper")
    void refusedSubmissionsKeepTheirMessage() {
        answer("/user.status", 400, NOT_FOUND);

        assertThatThrownBy(() -> client.getSubmissions("nobody_here", 1, 100))
            .isInstanceOf(ApiException.class)
            .hasMessage("CF handle not found: nobody_here");
        assertThat(callsTo("/user.status")).isEqualTo(1);
    }

    @Test
    @DisplayName("a passing server error is retried, and the answer after it is used")
    void serverErrorIsRetried() {
        answer("/user.info", 503, "");
        answer("/user.info", 200, """
            {"status":"OK","result":[{"handle":"tourist","rating":3500,"maxRating":3800}]}""");

        CfUserInfoResponse info = client.getUserInfo("tourist");

        assertThat(info.getStatus()).isEqualTo("OK");
        assertThat(info.getResult().get(0).getRating()).isEqualTo(3500);
        assertThat(callsTo("/user.info")).isEqualTo(2);
        verify(metrics).platformCall(eq("codeforces"), eq("user.info"), eq(true), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("a body that is not JSON fails the call, once, and is recorded as a failure")
    void malformedBodyFails() {
        answer("/user.rating", 200, "<html>Codeforces is temporarily unavailable</html>");

        assertThatThrownBy(() -> client.getRatingHistory("tourist")).isInstanceOf(RuntimeException.class);
        assertThat(callsTo("/user.rating")).isEqualTo(1);
        verify(metrics).platformCall(eq("codeforces"), eq("user.rating"), eq(false), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("an empty submission history is an empty list, not an error")
    void emptyHistory() {
        answer("/user.status", 200, """
            {"status":"OK","result":[]}""");

        assertThat(client.getSubmissions("newcomer", 1, 100).getResult()).isEmpty();
    }

    @Test
    @DisplayName("a FAILED contest list is an error, not an empty calendar")
    void failedContestList() {
        answer("/contest.list", 200, """
            {"status":"FAILED","comment":"Call limit exceeded"}""");

        assertThatThrownBy(() -> client.getContests())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no contests");
    }

    @Test
    @DisplayName("a handle that exists is found")
    void existingHandle() {
        answer("/user.info", 200, """
            {"status":"OK","result":[{"handle":"tourist"}]}""");

        assertThat(client.handleExists("tourist")).isTrue();
        assertThat(callsTo("/user.info")).isEqualTo(1);
    }
}
