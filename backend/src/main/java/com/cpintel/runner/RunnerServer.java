package com.cpintel.runner;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The code runner as its own process, for a shared server.
 *
 * <h2>Why a separate process</h2>
 *
 * <p>Run executes whatever anybody types. In the backend that would put every account one
 * sandbox escape away from the database credentials, the JWT signing key and every stored
 * session, all of which the backend holds. This process holds none of them. It is started from
 * the same jar with a different main class, gets no configuration but its own, runs in a
 * container with a read-only filesystem, no capabilities and no route to the internet, and
 * still puts every run in a fresh bubblewrap sandbox so concurrent runs cannot see each other —
 * which, in an examination, is the difference between a rehearsal tool and a way to read the
 * next candidate's solution.
 *
 * <h2>What it does</h2>
 *
 * <p>Two routes, both behind a token shared with the backend: {@code GET /health} (whether
 * runs are sandboxed, and which languages are installed) and {@code POST /run} (compile once,
 * run each test). No Spring, no database: nothing starts that could want a secret.
 *
 * <p><b>Fails closed.</b> If bubblewrap cannot build a sandbox here, every run is refused with a
 * message saying so, rather than falling back to running unsandboxed.
 *
 * <p><b>Bounded.</b> At most {@code RUNNER_CONCURRENCY} runs execute at once (default: the
 * number of cores). Further runs wait up to {@link #QUEUE_WAIT_MS} for a slot and are then
 * turned away with 503, which the backend reports as "busy, try again" — two hundred
 * candidates pressing Run in the same minute queue rather than starving each other of CPU and
 * all timing out.
 */
public final class RunnerServer {

    private static final Logger log = LoggerFactory.getLogger(RunnerServer.class);

    public static final String TOKEN_HEADER = "X-Runner-Token";

    /** How long a run may wait for a free slot before being turned away. */
    public static final long QUEUE_WAIT_MS = 20_000;

    /** Largest request accepted: a 256 KB source plus fifty tests' input, with margin. */
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private static final int MAX_SOURCE_CHARS = 262_144;
    private static final int MAX_TESTS = 50;

    /** The shape of a run request. Event rules are the backend's business, not this one's. */
    record Request(String language, String source, List<RunDto.TestCase> tests) {}

    private final RunEngine engine;
    private final byte[] token;
    private final Semaphore slots;
    private final ObjectMapper json = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    RunnerServer(RunEngine engine, String token, int concurrency) {
        this.engine = engine;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.slots = new Semaphore(concurrency, true);
    }

    public static void main(String[] args) throws IOException {
        quietLogging();

        String token = env("RUNNER_TOKEN", "");
        if (token.length() < 16) {
            // Refusing to start beats starting open: anything on the runner's network could
            // otherwise execute code here.
            log.error("RUNNER_TOKEN must be set (16+ characters). Refusing to start.");
            System.exit(1);
        }
        int port = Integer.parseInt(env("RUNNER_PORT", "8090"));
        int concurrency = Integer.parseInt(env("RUNNER_CONCURRENCY",
            String.valueOf(Runtime.getRuntime().availableProcessors())));

        RunEngine engine = new RunEngine(
            List.of(new CppRuntime(), new PythonRuntime()),
            new Sandbox(),
            new RunEngine.Limits(
                Long.parseLong(env("RUNNER_TIME_LIMIT_MS", "5000")),
                Long.parseLong(env("RUNNER_COMPILE_TIME_LIMIT_MS", "20000")),
                Integer.parseInt(env("RUNNER_MEMORY_LIMIT_MB", "512")),
                Integer.parseInt(env("RUNNER_OUTPUT_LIMIT_BYTES", "65536"))),
            true);

        RunnerServer server = new RunnerServer(engine, token, concurrency);
        HttpServer http = HttpServer.create(new InetSocketAddress(port), 128);
        http.createContext("/health", server::health);
        http.createContext("/run", server::run);
        // Enough threads for the slots plus the queue waiting on them.
        http.setExecutor(Executors.newFixedThreadPool(Math.max(8, concurrency * 8)));
        http.start();

        String blocked = engine.blockedReason();
        log.info("Runner listening on {} — {} concurrent runs, sandbox {}", port, concurrency,
            blocked == null ? "active" : "UNAVAILABLE, all runs refused");
    }

    // ── routes ─────────────────────────────────────────────────────────────

    private void health(HttpExchange ex) throws IOException {
        try (ex) {
            if (!authorised(ex)) { send(ex, 401, Map.of("error", "unauthorised")); return; }
            send(ex, 200, Map.of(
                "isolated", engine.isIsolated(),
                "languages", engine.languages(),
                "freeSlots", slots.availablePermits()));
        }
    }

    private void run(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"POST".equals(ex.getRequestMethod())) {
                send(ex, 405, Map.of("error", "POST only")); return;
            }
            if (!authorised(ex)) { send(ex, 401, Map.of("error", "unauthorised")); return; }

            byte[] body = readCapped(ex.getRequestBody());
            if (body == null) { send(ex, 413, Map.of("error", "request too large")); return; }

            Request req;
            try {
                req = json.readValue(body, Request.class);
            } catch (IOException e) {
                send(ex, 400, Map.of("error", "malformed request")); return;
            }
            if (req.language() == null || req.source() == null || req.tests() == null
                || req.source().length() > MAX_SOURCE_CHARS || req.tests().size() > MAX_TESTS) {
                send(ex, 400, Map.of("error", "invalid request")); return;
            }

            boolean acquired = false;
            try {
                acquired = slots.tryAcquire(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS);
                if (!acquired) { send(ex, 503, Map.of("error", "busy")); return; }
                send(ex, 200, engine.run(req.language(), req.source(), req.tests()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                send(ex, 503, Map.of("error", "interrupted"));
            } finally {
                if (acquired) slots.release();
            }
        }
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    private boolean authorised(HttpExchange ex) {
        String given = ex.getRequestHeaders().getFirst(TOKEN_HEADER);
        return given != null
            && MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        byte[] data = in.readNBytes(MAX_BODY_BYTES + 1);
        return data.length > MAX_BODY_BYTES ? null : data;
    }

    private void send(HttpExchange ex, int status, Object payload) throws IOException {
        byte[] out = json.writeValueAsBytes(payload);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Logback's configuration-less default logs everything at DEBUG; this is a server. */
    private static void quietLogging() {
        org.slf4j.Logger root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (root instanceof ch.qos.logback.classic.Logger logback) {
            logback.setLevel(ch.qos.logback.classic.Level.INFO);
        }
    }
}
