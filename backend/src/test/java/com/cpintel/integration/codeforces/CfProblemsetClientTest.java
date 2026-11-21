package com.cpintel.integration.codeforces;

import com.cpintel.integration.OutboundRateLimiter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The roadmap and practice plans lean on the Codeforces problem list. When Codeforces is down
 * they must keep working from the last list they had, and must not make every page wait on a
 * site that is not answering.
 */
class CfProblemsetClientTest {

    private static final String LIST = """
        {"status":"OK","result":{"problems":[
          {"contestId":1,"index":"A","name":"Theatre Square","rating":1000,"tags":["math"]},
          {"contestId":2,"index":"B","name":"The least round way","rating":2000,"tags":["dp"]}]}}""";

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int status = 200;
    private volatile String body = LIST;
    private CfProblemsetClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/problemset.problems", ex -> {
            calls.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        client = new CfProblemsetClient(WebClient.builder().build(), mock(OutboundRateLimiter.class));
        ReflectionTestUtils.setField(client, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(client, "rateLimitMs", 0L);
        ReflectionTestUtils.setField(client, "retryBackoff", Duration.ofMillis(5));
        client.init();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void expire() {
        ReflectionTestUtils.setField(client, "cachedAt", Instant.now().minus(Duration.ofHours(7)));
    }

    @Test
    @DisplayName("a fresh list is read once and then served from memory")
    void cachesTheList() {
        assertThat(client.getAllProblems()).hasSize(2);
        assertThat(client.getAllProblems()).hasSize(2);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("when the refresh fails the last list is kept, not dropped")
    void keepsTheStaleListWhenCodeforcesIsDown() {
        assertThat(client.getAllProblems()).hasSize(2);
        expire();
        status = 503;
        body = "down";
        assertThat(client.getAllProblems()).hasSize(2);
    }

    @Test
    @DisplayName("a failed refresh is not tried again on every request")
    void doesNotHammerADeadSite() {
        assertThat(client.getAllProblems()).hasSize(2);
        expire();
        status = 503;
        body = "down";
        int before = calls.get();
        client.getAllProblems();
        int afterFirstFailure = calls.get();
        assertThat(afterFirstFailure).isGreaterThan(before);
        for (int i = 0; i < 5; i++) client.getAllProblems();
        assertThat(calls.get()).isEqualTo(afterFirstFailure);
    }

    @Test
    @DisplayName("with no list at all and Codeforces down, it answers empty and fast on later requests")
    void emptyAndFastWhenThereIsNothingToFallBackOn() {
        status = 503;
        body = "down";
        assertThat(client.getAllProblems()).isEmpty();
        int afterFirstFailure = calls.get();
        long t = System.nanoTime();
        for (int i = 0; i < 5; i++) assertThat(client.getAllProblems()).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - t)).isLessThan(Duration.ofSeconds(1));
        assertThat(calls.get()).isEqualTo(afterFirstFailure);
    }

    @Test
    @DisplayName("an answer with no problems in it (FAILED, or a changed shape) does not wipe the list")
    void aHollowAnswerKeepsTheList() {
        assertThat(client.getAllProblems()).hasSize(2);
        expire();
        body = "{\"status\":\"FAILED\",\"comment\":\"limit\"}";
        assertThat(client.getAllProblems()).hasSize(2);
    }

    @Test
    @DisplayName("once the back-off passes and Codeforces is back, the list refreshes")
    void recovers() {
        status = 503;
        body = "down";
        assertThat(client.getAllProblems()).isEmpty();
        status = 200;
        body = LIST;
        ReflectionTestUtils.setField(client, "retryNotBefore", Instant.now().minusSeconds(1));
        assertThat(client.getAllProblems()).hasSize(2);
    }
}
