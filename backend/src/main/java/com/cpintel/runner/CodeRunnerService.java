package com.cpintel.runner;

import com.cpintel.config.AppMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Compiles a solution once and runs it against each sample test, on this machine.
 *
 * <p>This is a rehearsal harness, not a judge. It tells you whether your code builds and
 * agrees with the samples before you spend a submission — Codeforces remains the authority
 * on the real verdict, against tests we do not have.
 */
@Service
@Slf4j
public class CodeRunnerService {

    private final Map<String, LanguageRuntime> runtimes;
    private final AppMetrics metrics;
    private final Sandbox sandbox;

    /** Master switch. Off means no user code is ever executed by this process. */
    @Value("${cpintel.runner.enabled:true}")
    private boolean enabled;

    @Value("${cpintel.runner.time-limit-ms:5000}")
    private long timeLimitMs;

    @Value("${cpintel.runner.compile-time-limit-ms:20000}")
    private long compileTimeLimitMs;

    @Value("${cpintel.runner.memory-limit-mb:512}")
    private int memoryLimitMb;

    /** Per-stream output cap. Enough for any sane answer, small enough to stay in a JSON body. */
    @Value("${cpintel.runner.output-limit-bytes:65536}")
    private int outputLimitBytes;

    public CodeRunnerService(List<LanguageRuntime> runtimeBeans, Sandbox sandbox,
                             AppMetrics metrics) {
        this.runtimes = runtimeBeans.stream()
            .collect(Collectors.toMap(LanguageRuntime::id, Function.identity()));
        this.sandbox = sandbox;
        this.metrics = metrics;
    }

    /** Languages this deployment knows about, and whether each can actually run here. */
    public List<RunDto.RuntimeInfo> languages() {
        return runtimes.values().stream()
            .sorted(Comparator.comparing(LanguageRuntime::displayName))
            .map(r -> new RunDto.RuntimeInfo(
                r.id(), r.displayName(), r.editorLanguage(),
                enabled && r.isAvailable(),
                !enabled ? "Local running is turned off on this deployment."
                    : r.isAvailable() ? null : r.unavailableReason()))
            .toList();
    }

    /** True when the runner will execute anything at all. */
    public boolean isEnabled() {
        return enabled;
    }

    /** True when runs are filesystem- and network-isolated. */
    public boolean isIsolated() {
        return sandbox.isIsolated();
    }

    public RunDto.RunResponse run(RunDto.RunRequest request) {
        if (!enabled) {
            return RunDto.RunResponse.unavailable("Local running is turned off on this deployment.");
        }

        LanguageRuntime runtime = runtimes.get(request.language());
        if (runtime == null) {
            return RunDto.RunResponse.unavailable("Unknown language: " + request.language());
        }
        if (!runtime.isAvailable()) {
            return RunDto.RunResponse.unavailable(runtime.unavailableReason());
        }
        if (request.source().isBlank()) {
            return RunDto.RunResponse.unavailable("There is no code to run.");
        }

        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("cpintel-run-");
            runtime.prepare(workDir, request.source());

            Optional<List<String>> compile = runtime.compileCommand(workDir);
            long compileMs = 0;
            String compileOutput = null;

            if (compile.isPresent()) {
                Sandbox.ExecResult built = sandbox.exec(compile.get(), workDir, "", new Sandbox.Limits(
                    Duration.ofMillis(compileTimeLimitMs), memoryLimitMb, false, outputLimitBytes));
                compileMs = built.durationMs();
                compileOutput = merge(built.stdout(), built.stderr());

                if (built.timedOut()) {
                    return RunDto.RunResponse.failedToCompile(
                        "Compilation timed out after " + compileTimeLimitMs + " ms.", compileMs);
                }
                if (!built.ok()) {
                    metrics.runCompiled(false, java.time.Duration.ofMillis(compileMs));
                    return RunDto.RunResponse.failedToCompile(
                        compileOutput.isBlank() ? "Compilation failed." : compileOutput, compileMs);
                }
            }

            metrics.runCompiled(true, java.time.Duration.ofMillis(compileMs));

            List<RunDto.TestResult> results = new ArrayList<>();
            for (int i = 0; i < request.tests().size(); i++) {
                RunDto.TestResult result = runOne(runtime, workDir, request.tests().get(i), i);
                // Verdict distribution is the runner's health signal: a jump in
                // TIME_LIMIT_EXCEEDED usually means the host is loaded, not that everyone's
                // solutions got slower at once.
                metrics.runVerdict(result.verdict().name());
                results.add(result);
            }

            // Warnings are worth surfacing on success; a clean build should say nothing.
            String warnings = (compileOutput != null && !compileOutput.isBlank()) ? compileOutput : null;
            return new RunDto.RunResponse(true, warnings, compileMs, results, null);

        } catch (IOException e) {
            log.error("Run failed: {}", e.getMessage());
            return RunDto.RunResponse.unavailable("Could not run the code: " + e.getMessage());
        } finally {
            deleteQuietly(workDir);
        }
    }

    private RunDto.TestResult runOne(LanguageRuntime runtime, Path workDir,
                                     RunDto.TestCase test, int index) throws IOException {
        String label = (test.label() == null || test.label().isBlank())
            ? "Test " + (index + 1) : test.label();

        Sandbox.ExecResult exec = sandbox.exec(
            runtime.runCommand(workDir), workDir, test.input(),
            new Sandbox.Limits(Duration.ofMillis(timeLimitMs), memoryLimitMb,
                runtime.limitAddressSpace(), outputLimitBytes));

        RunDto.Verdict verdict;
        if (exec.timedOut()) {
            verdict = RunDto.Verdict.TIME_LIMIT_EXCEEDED;
        } else if (exec.exitCode() != 0) {
            verdict = RunDto.Verdict.RUNTIME_ERROR;
        } else if (test.expected() == null) {
            verdict = RunDto.Verdict.NO_EXPECTED;
        } else {
            verdict = matches(exec.stdout(), test.expected())
                ? RunDto.Verdict.OK : RunDto.Verdict.WRONG_ANSWER;
        }

        return new RunDto.TestResult(
            label, verdict, test.input(), test.expected(), exec.stdout(), exec.stderr(),
            exec.durationMs(), exec.exitCode() == 0 ? null : exec.exitCode(), exec.truncated());
    }

    /**
     * Compare output the way a checker for a problem with a unique answer does: ignore
     * trailing whitespace on each line and any trailing blank lines, and nothing else.
     *
     * <p>Deliberately not token-based. Collapsing all whitespace would call an answer
     * correct when the line structure is wrong, which is exactly the mistake this is meant
     * to catch before you burn a submission. Problems with multiple valid answers will
     * report a mismatch here — that is a limitation of comparing against a sample, not a
     * bug, and the UI says so.
     */
    static boolean matches(String actual, String expected) {
        return normalize(actual).equals(normalize(expected));
    }

    private static List<String> normalize(String text) {
        if (text == null) return List.of();
        List<String> lines = new ArrayList<>(List.of(text.replace("\r\n", "\n").split("\n", -1)));
        lines.replaceAll(CodeRunnerService::stripTrailing);
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) end--;
        return line.substring(0, end);
    }

    private static String merge(String stdout, String stderr) {
        if (stdout.isBlank()) return stderr;
        if (stderr.isBlank()) return stdout;
        return stdout + "\n" + stderr;
    }

    private void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { /* best effort */ }
            });
        } catch (IOException e) {
            log.warn("Could not clean up {}: {}", dir, e.getMessage());
        }
    }
}
