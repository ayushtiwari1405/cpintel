package com.cpintel.integration.leetcode;

import com.cpintel.config.AppMetrics;
import com.cpintel.integration.OutboundRateLimiter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * What the LeetCode client does with the shapes LeetCode's GraphQL actually returns, and with
 * the ones it returns when it changes: each must come out as a clear answer or a clear error,
 * never a wrong silent one. Runs against a stand-in on a local port.
 */
class LeetCodeClientTest {

    private HttpServer server;
    private volatile int status = 200;
    private volatile String body = "{}";
    private LeetCodeClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/graphql", ex -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        client = new LeetCodeClient(WebClient.builder().build(), mock(OutboundRateLimiter.class), mock(AppMetrics.class));
        ReflectionTestUtils.setField(client, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(client, "rateLimitMs", 0L);
        client.init();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("a known user comes back with their about-me text")
    void knownUser() {
        body = "{\"data\":{\"matchedUser\":{\"username\":\"Alice\",\"profile\":{\"aboutMe\":\"cpintel-123\"}}}}";
        assertThat(client.profile("alice")).satisfies(p -> {
            assertThat(p.username()).isEqualTo("Alice");
            assertThat(p.aboutMe()).isEqualTo("cpintel-123");
        });
    }

    @Test
    @DisplayName("an unknown user (matchedUser null, with an error beside it) is null, not a failure")
    void unknownUser() {
        body = "{\"errors\":[{\"message\":\"That user does not exist.\"}],\"data\":{\"matchedUser\":null}}";
        assertThat(client.profile("nobody")).isNull();
    }

    @Test
    @DisplayName("a reply with errors and no data at all is a loud failure, naming the operation")
    void errorsWithoutData() {
        body = "{\"errors\":[{\"message\":\"Cannot query field matchedUser\"}]}";
        assertThatThrownBy(() -> client.profile("x"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("profile").hasMessageContaining("Cannot query field");
    }

    @Test
    @DisplayName("a null about-me reads as empty rather than the text 'null'")
    void nullAboutMe() {
        body = "{\"data\":{\"matchedUser\":{\"username\":\"a\",\"profile\":{\"aboutMe\":null}}}}";
        assertThat(client.profile("a").aboutMe()).isEmpty();
    }

    @Test
    @DisplayName("recent accepted: newest first as sent, rows with no timestamp are skipped")
    void recentAccepted() {
        body = "{\"data\":{\"recentAcSubmissionList\":["
            + "{\"id\":\"9\",\"titleSlug\":\"two-sum\",\"timestamp\":\"1700000100\"},"
            + "{\"id\":\"8\",\"titleSlug\":\"broken\",\"timestamp\":null},"
            + "{\"id\":\"7\",\"titleSlug\":\"add-two-numbers\",\"timestamp\":\"1700000000\"}]}}";
        var out = client.recentAccepted("a");
        assertThat(out).extracting(LeetCodeClient.AcceptedSubmission::slug)
            .containsExactly("two-sum", "add-two-numbers");
        assertThat(out.get(0).at().getEpochSecond()).isEqualTo(1_700_000_100L);
    }

    @Test
    @DisplayName("a private or missing list is empty, never a crash")
    void recentAcceptedNull() {
        body = "{\"data\":{\"recentAcSubmissionList\":null}}";
        assertThat(client.recentAccepted("a")).isEmpty();
    }

    @Test
    @DisplayName("a problem that does not exist is null; a premium one comes back with no content")
    void problems() {
        body = "{\"data\":{\"question\":null}}";
        assertThat(client.statement("nope")).isNull();
        body = "{\"data\":{\"question\":{\"questionId\":\"1\",\"questionFrontendId\":\"1\",\"title\":\"Two Sum\","
            + "\"titleSlug\":\"two-sum\",\"difficulty\":\"Easy\",\"isPaidOnly\":true,\"content\":null,"
            + "\"exampleTestcaseList\":[\"[1,2]\\n3\"],\"codeSnippets\":[{\"lang\":\"Java\",\"langSlug\":\"java\",\"code\":\"class S{}\"}]}}}";
        var st = client.statement("two-sum");
        assertThat(st.paidOnly()).isTrue();
        assertThat(st.contentHtml()).isNull();
        assertThat(st.snippets()).hasSize(1);
        assertThat(st.exampleTestcases()).containsExactly("[1,2]\n3");
    }

    @Test
    @DisplayName("a changed response shape does not invent data: renamed fields give nothing, a non-object fails")
    void changedShape() {
        body = "{\"data\":{\"recentAcSubmissionList\":[{\"id\":5,\"slug\":\"renamed-field\",\"ts\":1}]}}";
        assertThat(client.recentAccepted("a")).isEmpty();
        body = "[]";
        assertThatThrownBy(() -> client.recentAccepted("a")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a 403 (blocked) is not retried and surfaces as an error")
    void blockedIsNotRetried() {
        status = 403;
        body = "forbidden";
        assertThatThrownBy(() -> client.profile("a")).isInstanceOf(WebClientResponseException.class);
    }

    @Test
    @DisplayName("only server errors and rate limits are retried")
    void whatIsTransient() {
        assertThat(LeetCodeClient.transientFailure(WebClientResponseException.create(503, "x", null, null, null))).isTrue();
        assertThat(LeetCodeClient.transientFailure(WebClientResponseException.create(429, "x", null, null, null))).isTrue();
        assertThat(LeetCodeClient.transientFailure(WebClientResponseException.create(403, "x", null, null, null))).isFalse();
        assertThat(LeetCodeClient.transientFailure(WebClientResponseException.create(404, "x", null, null, null))).isFalse();
    }
}
