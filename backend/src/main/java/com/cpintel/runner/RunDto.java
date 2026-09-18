package com.cpintel.runner;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Wire types for running a solution against sample tests on this machine. */
public final class RunDto {

    private RunDto() {}

    /** One test to feed the program: an input, and optionally the answer to check against. */
    public record TestCase(
        String input,
        /** Null for a scratch run with custom input — the output is shown, not judged. */
        String expected,
        String label
    ) {}

    public record RunRequest(
        /** Runtime id from {@code GET /api/run/languages}, e.g. "cpp". */
        @NotBlank String language,
        @NotBlank @Size(max = 262_144, message = "Source must be under 256 KB") String source,
        @NotNull @Size(max = 50, message = "At most 50 tests per run") List<TestCase> tests
    ) {}

    /** What happened to one test. */
    public enum Verdict {
        OK,
        WRONG_ANSWER,
        TIME_LIMIT_EXCEEDED,
        RUNTIME_ERROR,
        /** No expected output was supplied, so the program's output is reported unjudged. */
        NO_EXPECTED
    }

    public record TestResult(
        String label,
        Verdict verdict,
        String input,
        String expected,
        String actual,
        /** Compiler/runtime diagnostics for this test — stderr, truncated. */
        String stderr,
        long durationMs,
        /** Non-zero exit status, when the process exited abnormally. */
        Integer exitCode,
        /** True when output was cut off at the cap rather than ending naturally. */
        boolean truncated
    ) {}

    public record RunResponse(
        boolean compiled,
        /** Compiler output. Present on failure, and on success when there are warnings. */
        String compileOutput,
        long compileMs,
        List<TestResult> results,
        /** Set when the run could not be attempted at all (toolchain missing, disabled). */
        String error
    ) {
        public static RunResponse failedToCompile(String output, long ms) {
            return new RunResponse(false, output, ms, List.of(), null);
        }

        public static RunResponse unavailable(String why) {
            return new RunResponse(false, null, 0, List.of(), why);
        }
    }

    /** A language this deployment can actually run, for the editor's dropdown. */
    public record RuntimeInfo(
        String id,
        String displayName,
        /** Monaco's language id, so the editor highlights correctly. */
        String editorLanguage,
        boolean available,
        /** Why it is unavailable — a missing compiler, usually. */
        String unavailableReason
    ) {}
}
