package com.cpintel.runner;

import com.cpintel.config.AppMetrics;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Where a Run goes: this process, or the isolated runner container.
 *
 * <p><b>Local</b> (development, the desktop build): the {@link RunEngine} runs here. The only
 * code executed is the code of the person sitting at the machine.
 *
 * <p><b>Remote</b> (a shared server): the backend never executes submitted code itself. It
 * forwards the run to {@link RunnerServer} in its own container — no secrets, no database
 * credentials, no route to the internet, a read-only filesystem, and a fresh sandbox per run.
 * A program that escaped everything would still find nothing worth taking. Set by
 * {@code cpintel.runner.url}; the production profile requires it.
 */
@Service
@Slf4j
public class CodeRunnerService {

    private final RunEngine local;
    private final AppMetrics metrics;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3)).build();

    /** Master switch. Off means no user code is ever executed for this deployment. */
    @Value("${cpintel.runner.enabled:true}")
    private boolean enabled;

    /** The runner container, e.g. http://runner:8090. Blank runs code in this process. */
    @Value("${cpintel.runner.url:}")
    private String remoteUrl;

    /** Shared with the runner, so nothing else on its network can use it. */
    @Value("${cpintel.runner.token:}")
    private String token;

    @Value("${cpintel.runner.time-limit-ms:5000}")
    private long timeLimitMs;

    @Value("${cpintel.runner.compile-time-limit-ms:20000}")
    private long compileTimeLimitMs;

    /** The runner's answer to "what can you run", reused for a little while. */
    private volatile Map<String, Object> remoteHealth;
    private volatile Instant remoteHealthAt = Instant.EPOCH;

    public CodeRunnerService(List<LanguageRuntime> runtimeBeans, Sandbox sandbox,
                             AppMetrics metrics, ObjectMapper json,
                             @Value("${cpintel.runner.time-limit-ms:5000}") long timeLimitMs,
                             @Value("${cpintel.runner.compile-time-limit-ms:20000}")
                             long compileTimeLimitMs,
                             @Value("${cpintel.runner.memory-limit-mb:512}") int memoryLimitMb,
                             @Value("${cpintel.runner.output-limit-bytes:65536}")
                             int outputLimitBytes) {
        this.local = new RunEngine(runtimeBeans, sandbox, new RunEngine.Limits(
            timeLimitMs, compileTimeLimitMs, memoryLimitMb, outputLimitBytes), false);
        this.metrics = metrics;
        this.json = json;
    }

    private boolean remote() {
        return remoteUrl != null && !remoteUrl.isBlank();
    }

    /** True when the runner will execute anything at all. */
    public boolean isEnabled() {
        return enabled;
    }

    /** True when runs are filesystem- and network-isolated. */
    public boolean isIsolated() {
        if (!remote()) return local.isIsolated();
        Map<String, Object> health = health();
        return health != null && Boolean.TRUE.equals(health.get("isolated"));
    }

    /** Languages this deployment can run, and whether each can run right now. */
    public List<RunDto.RuntimeInfo> languages() {
        if (!enabled) {
            return local.languages().stream()
                .map(r -> new RunDto.RuntimeInfo(r.id(), r.displayName(), r.editorLanguage(),
                    false, "Running code is turned off on this deployment."))
                .toList();
        }
        if (!remote()) return local.languages();

        Map<String, Object> health = health();
        if (health == null) {
            return local.languages().stream()
                .map(r -> new RunDto.RuntimeInfo(r.id(), r.displayName(), r.editorLanguage(),
                    false, "The code runner is not reachable right now. Submitting still works."))
                .toList();
        }
        return json.convertValue(health.get("languages"),
            new TypeReference<List<RunDto.RuntimeInfo>>() {});
    }

    public RunDto.RunResponse run(RunDto.RunRequest request) {
        if (!enabled) {
            return RunDto.RunResponse.unavailable("Running code is turned off on this deployment.");
        }
        RunDto.RunResponse response = remote()
            ? runRemote(request)
            : local.run(request.language(), request.source(), request.tests());
        record(response);
        return response;
    }

    // ── remote ─────────────────────────────────────────────────────────────

    private RunDto.RunResponse runRemote(RunDto.RunRequest request) {
        // Long enough for the queue the runner may hold this in, the compile, and every test.
        Duration budget = Duration.ofMillis(RunnerServer.QUEUE_WAIT_MS + compileTimeLimitMs
            + (timeLimitMs + 1000) * Math.max(1, request.tests().size()) + 5000);
        try {
            String body = json.writeValueAsString(Map.of(
                "language", request.language(),
                "source", request.source(),
                "tests", request.tests()));
            HttpResponse<String> res = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(remoteUrl + "/run"))
                    .timeout(budget)
                    .header("Content-Type", "application/json")
                    .header(RunnerServer.TOKEN_HEADER, token)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() == 503) {
                return RunDto.RunResponse.unavailable(
                    "Lots of people are running code right now. Try again in a few seconds.");
            }
            if (res.statusCode() != 200) {
                log.warn("Runner answered {}: {}", res.statusCode(), res.body());
                return RunDto.RunResponse.unavailable("The code runner could not take this run.");
            }
            return json.readValue(res.body(), RunDto.RunResponse.class);
        } catch (java.net.http.HttpTimeoutException e) {
            return RunDto.RunResponse.unavailable("The code runner took too long to answer.");
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Runner unreachable: {}", e.getMessage());
            return RunDto.RunResponse.unavailable(
                "The code runner is not reachable right now. Submitting still works.");
        }
    }

    /** The runner's health, cached for 30 seconds; null when it cannot be reached. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> health() {
        if (Instant.now().isBefore(remoteHealthAt.plusSeconds(30)) && remoteHealth != null) {
            return remoteHealth;
        }
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(remoteUrl + "/health"))
                    .timeout(Duration.ofSeconds(5))
                    .header(RunnerServer.TOKEN_HEADER, token)
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return null;
            remoteHealth = json.readValue(res.body(), Map.class);
            remoteHealthAt = Instant.now();
            return remoteHealth;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.debug("Runner health check failed: {}", e.getMessage());
            return null;
        }
    }

    private void record(RunDto.RunResponse response) {
        if (response.error() != null) return;
        metrics.runCompiled(response.compiled(), Duration.ofMillis(response.compileMs()));
        // Verdict distribution is the runner's health signal: a jump in TIME_LIMIT_EXCEEDED
        // usually means the host is loaded, not that everyone's solutions got slower at once.
        response.results().forEach(r -> metrics.runVerdict(r.verdict().name()));
    }
}
