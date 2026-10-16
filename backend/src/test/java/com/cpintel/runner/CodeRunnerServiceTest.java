package com.cpintel.runner;

import com.cpintel.config.AppMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every way a Run can come back without a result is counted, because the response alone does
 * not show it: in the load test the runner ran out of pids and answered every run 200, with the
 * failure inside, for an hour before anyone noticed.
 */
class CodeRunnerServiceTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private CodeRunnerService service;
    private HttpServer runner;

    private static final RunDto.RunRequest REQUEST = new RunDto.RunRequest(
        "cpp", "int main(){}", List.of(new RunDto.TestCase("", "", "Test 1")), null, null);

    @BeforeEach
    void setUp() {
        service = new CodeRunnerService(List.of(), new Sandbox(), new AppMetrics(registry),
            new ObjectMapper(), 5000, 20000, 512, 65536);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "token", "t");
    }

    @AfterEach
    void tearDown() {
        if (runner != null) runner.stop(0);
    }

    private double failed(String reason) {
        var counter = registry.find("cpintel.run.failed").tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    /** A runner that answers every /run with this status and body. */
    private void runnerAnswering(int status, String body) throws IOException {
        runner = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        runner.createContext("/run", ex -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        runner.start();
        ReflectionTestUtils.setField(service, "remoteUrl",
            "http://127.0.0.1:" + runner.getAddress().getPort());
    }

    @Test
    @DisplayName("A 200 with the failure inside is counted as an error")
    void errorInsideA200() throws IOException {
        runnerAnswering(200, """
            {"compiled":false,"compileOutput":null,"compileMs":0,"results":[],
             "error":"Could not run the code: Cannot run program \\"bwrap\\": error=11"}""");

        RunDto.RunResponse response = service.run(REQUEST);

        assertNotNull(response.error());
        assertEquals(1, failed("error"));
    }

    @Test
    @DisplayName("A full queue is counted as busy, not as an error")
    void busy() throws IOException {
        runnerAnswering(503, "{\"error\":\"busy\"}");

        service.run(REQUEST);

        assertEquals(1, failed("busy"));
        assertEquals(0, failed("error"));
    }

    @Test
    @DisplayName("Any other status is counted as rejected")
    void rejected() throws IOException {
        runnerAnswering(401, "{\"error\":\"unauthorised\"}");

        service.run(REQUEST);

        assertEquals(1, failed("rejected"));
    }

    @Test
    @DisplayName("A runner that cannot be reached is counted as unreachable")
    void unreachable() throws IOException {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        ReflectionTestUtils.setField(service, "remoteUrl", "http://127.0.0.1:" + port);

        service.run(REQUEST);

        assertEquals(1, failed("unreachable"));
    }

    @Test
    @DisplayName("A run with a result is not counted as a failure")
    void success() throws IOException {
        runnerAnswering(200, """
            {"compiled":true,"compileOutput":null,"compileMs":800,
             "results":[{"label":"Test 1","verdict":"OK","input":"","expected":"","actual":"",
                         "stderr":"","durationMs":5,"exitCode":0,"truncated":false}],
             "error":null}""");

        service.run(REQUEST);

        assertNull(registry.find("cpintel.run.failed").counter());
    }

    @Test
    @DisplayName("A local run the engine cannot carry out is counted as an error too")
    void localError() {
        RunDto.RunResponse response = service.run(REQUEST);   // no runtimes: unknown language

        assertNotNull(response.error());
        assertEquals(1, failed("error"));
    }
}
