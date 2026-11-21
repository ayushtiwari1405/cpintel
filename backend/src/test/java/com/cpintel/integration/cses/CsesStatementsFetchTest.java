package com.cpintel.integration.cses;

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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** How the CSES statement fetch tells "no such task" from "cses.fi is not answering". */
class CsesStatementsFetchTest {

    private HttpServer server;
    private final Map<String, int[]> status = new ConcurrentHashMap<>();
    private final Map<String, String> page = new ConcurrentHashMap<>();
    private CsesStatements statements;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            int code = status.getOrDefault(path, new int[] { 404 })[0];
            byte[] bytes = page.getOrDefault(path, "").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        statements = new CsesStatements(WebClient.builder().build(), mock(OutboundRateLimiter.class));
        ReflectionTestUtils.setField(statements, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void serve(String id, int code, String html) {
        status.put("/problemset/task/" + id + "/", new int[] { code });
        page.put("/problemset/task/" + id + "/", html);
    }

    private static String fixture() throws Exception {
        try (var in = CsesStatementsFetchTest.class.getResourceAsStream("/cses/task-1640.html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("a real task page is fetched and read")
    void readsAPage() throws Exception {
        serve("1640", 200, fixture());
        assertThat(statements.statement("1640").name()).isEqualTo("Sum of Two Values");
    }

    @Test
    @DisplayName("a 404 means CSES has no such task: null, not an exception")
    void notFoundIsNull() {
        serve("999999", 404, "Not found");
        assertThat(statements.statement("999999")).isNull();
    }

    @Test
    @DisplayName("a 200 page without the statement block (a layout change) reads as no statement, not a crash")
    void changedLayoutIsNull() {
        serve("1", 200, "<html><title>CSES - X</title><body><div class='content'>new layout</div></body></html>");
        assertThat(statements.statement("1")).isNull();
    }

    @Test
    @DisplayName("a server error is a failure the caller can report as 'CSES did not answer'")
    void serverErrorThrows() {
        serve("2", 503, "busy");
        assertThatThrownBy(() -> statements.statement("2")).isNotNull();
    }

    @Test
    @DisplayName("a missing time limit or example list does not stop the statement being shown")
    void missingPartsAreTolerated() {
        CsesStatements.Statement st = CsesStatements.parse("3",
            "<html><title>CSES - Odd</title><body><div class='md'><p>Just text.</p></div></body></html>");
        assertThat(st).isNotNull();
        assertThat(st.timeLimit()).isNull();
        assertThat(st.examples()).isEmpty();
        assertThat(st.html()).contains("Just text.");
    }
}
