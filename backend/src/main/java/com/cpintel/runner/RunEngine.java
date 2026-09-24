package com.cpintel.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * Compiles a solution once and runs it against each test. No Spring, no database, no network.
 *
 * <p>Plain on purpose: the same class runs inside the backend (development and the desktop
 * build, where the code is the user's own on their own machine) and inside the isolated runner
 * container ({@link RunnerServer}), which carries none of the backend's configuration or
 * secrets. One implementation means a run behaves the same in both places.
 *
 * <p>This is a rehearsal harness, not a judge. It says whether code builds and agrees with the
 * samples before a submission is spent; the judge remains the authority on the verdict.
 */
public class RunEngine {

    private static final Logger log = LoggerFactory.getLogger(RunEngine.class);

    /** Per-run limits. */
    public record Limits(long timeLimitMs, long compileTimeLimitMs, int memoryLimitMb,
                         int outputLimitBytes) {}

    private final Map<String, LanguageRuntime> runtimes;
    private final Sandbox sandbox;
    private final Limits limits;
    /**
     * Refuse to run anything unless each run is sandboxed. Set in the runner container, where
     * code from every user runs side by side: without the sandbox one person's program could
     * read another's files mid-run. The desktop build leaves it off — the only code there is
     * the user's own.
     */
    private final boolean requireIsolation;

    public RunEngine(List<LanguageRuntime> runtimes, Sandbox sandbox, Limits limits,
                     boolean requireIsolation) {
        this.runtimes = runtimes.stream()
            .collect(Collectors.toMap(LanguageRuntime::id, Function.identity()));
        this.sandbox = sandbox;
        this.limits = limits;
        this.requireIsolation = requireIsolation;
    }

    /** True when runs are filesystem- and network-isolated. */
    public boolean isIsolated() {
        return sandbox.isIsolated();
    }

    /** Why nothing can run at all right now, or null when runs can go ahead. */
    public String blockedReason() {
        if (requireIsolation && !sandbox.isIsolated()) {
            return "The code sandbox is not available on this server, so running is paused. "
                + "Submitting still works.";
        }
        return null;
    }

    /** Every runtime, and whether it can run here. */
    public List<RunDto.RuntimeInfo> languages() {
        String blocked = blockedReason();
        return runtimes.values().stream()
            .sorted(Comparator.comparing(LanguageRuntime::displayName))
            .map(r -> new RunDto.RuntimeInfo(
                r.id(), r.displayName(), r.editorLanguage(),
                blocked == null && r.isAvailable(),
                blocked != null ? blocked : r.isAvailable() ? null : r.unavailableReason()))
            .toList();
    }

    public RunDto.RunResponse run(String language, String source, List<RunDto.TestCase> tests) {
        String blocked = blockedReason();
        if (blocked != null) return RunDto.RunResponse.unavailable(blocked);

        LanguageRuntime runtime = runtimes.get(language);
        if (runtime == null) {
            return RunDto.RunResponse.unavailable("Unknown language: " + language);
        }
        if (!runtime.isAvailable()) {
            return RunDto.RunResponse.unavailable(runtime.unavailableReason());
        }
        if (source == null || source.isBlank()) {
            return RunDto.RunResponse.unavailable("There is no code to run.");
        }

        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("cpintel-run-");
            runtime.prepare(workDir, source);

            Optional<List<String>> compile = runtime.compileCommand(workDir);
            long compileMs = 0;
            String compileOutput = null;

            if (compile.isPresent()) {
                Sandbox.ExecResult built = sandbox.exec(compile.get(), workDir, "",
                    new Sandbox.Limits(Duration.ofMillis(limits.compileTimeLimitMs()),
                        limits.memoryLimitMb(), false, limits.outputLimitBytes()));
                compileMs = built.durationMs();
                compileOutput = merge(built.stdout(), built.stderr());

                if (built.timedOut()) {
                    return RunDto.RunResponse.failedToCompile(
                        "Compilation timed out after " + limits.compileTimeLimitMs() + " ms.",
                        compileMs);
                }
                if (!built.ok()) {
                    return RunDto.RunResponse.failedToCompile(
                        compileOutput.isBlank() ? "Compilation failed." : compileOutput, compileMs);
                }
            }

            List<RunDto.TestResult> results = new ArrayList<>();
            for (int i = 0; i < tests.size(); i++) {
                results.add(runOne(runtime, workDir, tests.get(i), i));
            }

            // Warnings are worth surfacing on success; a clean build should say nothing.
            String warnings = (compileOutput != null && !compileOutput.isBlank())
                ? compileOutput : null;
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
            new Sandbox.Limits(Duration.ofMillis(limits.timeLimitMs()), limits.memoryLimitMb(),
                runtime.limitAddressSpace(), limits.outputLimitBytes()));

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
        lines.replaceAll(RunEngine::stripTrailing);
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

    private static void deleteQuietly(Path dir) {
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
