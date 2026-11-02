package com.cpintel.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C++, actually compiled and run — see {@link PythonRunnerTest} for why. Skipped where g++ is not
 * installed.
 */
@EnabledIf("gppInstalled")
class CppRunnerTest {

    static boolean gppInstalled() {
        return new CppRuntime().isAvailable();
    }

    private static RunDto.RunResponse run(int memoryMb, String source, String input, String expected) {
        RunEngine engine = new RunEngine(List.of(new CppRuntime()), new Sandbox(),
            new RunEngine.Limits(10_000L, 20_000L, memoryMb, 65_536), false);
        return engine.run("cpp", source, List.of(new RunDto.TestCase(input, expected, "Test 1")));
    }

    @Test
    @DisplayName("a correct solution passes")
    void correctSolutionPasses() {
        RunDto.RunResponse result = run(512, """
            #include <bits/stdc++.h>
            int main() { long long a, b; std::cin >> a >> b; std::cout << a + b << "\\n"; }
            """, "2 3\n", "5\n");

        assertTrue(result.compiled(), result.compileOutput());
        assertEquals(RunDto.Verdict.OK, result.results().get(0).verdict());
    }

    /** It used to be reported as a runtime error, which sends a student looking for a bug. */
    @Test
    @DisplayName("running out of memory is a memory limit, not a runtime error")
    void outOfMemoryIsMemoryLimit() {
        RunDto.RunResponse result = run(256, """
            #include <bits/stdc++.h>
            int main() { std::vector<long long> v(1LL << 30); std::cout << v.size() << "\\n"; }
            """, "", "");

        assertTrue(result.compiled(), result.compileOutput());
        assertEquals(RunDto.Verdict.MEMORY_LIMIT_EXCEEDED, result.results().get(0).verdict(),
            result.results().get(0).stderr());
    }

    /** A question bank check runs the model solution under that question's limits. */
    @Test
    @DisplayName("per-run limits apply to the program, but never shrink the compile")
    void perRunLimits() {
        RunEngine engine = new RunEngine(List.of(new CppRuntime()), new Sandbox(),
            new RunEngine.Limits(5_000L, 20_000L, 512, 65_536), false);
        String loud = """
            #include <bits/stdc++.h>
            int main() { std::string s(200000, 'x'); std::cout << s << "\\n"; }
            """;
        List<RunDto.TestCase> one = List.of(new RunDto.TestCase("", null, "Test 1"));

        // The engine's own 64 KB cuts the output; a check's own limit lets it all through.
        assertTrue(engine.run("cpp", loud, one).results().get(0).truncated());
        RunDto.TestResult whole = engine.run("cpp", loud, one, engine.clamp(2_000, 32, 1 << 20)).results().get(0);
        assertFalse(whole.truncated());
        assertEquals(200_001, whole.actual().length());

        // 32 MB is the program's limit; g++ still built it under the engine's 512 MB.
        assertEquals(RunDto.Verdict.NO_EXPECTED, whole.verdict(), whole.stderr());

        String slow = """
            #include <bits/stdc++.h>
            int main() { std::this_thread::sleep_for(std::chrono::milliseconds(600)); std::cout << 1; }
            """;
        assertEquals(RunDto.Verdict.TIME_LIMIT_EXCEEDED,
            engine.run("cpp", slow, one, engine.clamp(200, 256, 65_536)).results().get(0).verdict());
        assertEquals(RunDto.Verdict.NO_EXPECTED,
            engine.run("cpp", slow, one, engine.clamp(3_000, 256, 65_536)).results().get(0).verdict());
    }

    @Test
    @DisplayName("requested limits are held to the engine's caps")
    void clamped() {
        RunEngine engine = new RunEngine(List.of(new CppRuntime()), new Sandbox(),
            new RunEngine.Limits(5_000L, 20_000L, 512, 65_536), false);
        RunEngine.Limits l = engine.clamp(10_000_000, 1 << 20, Integer.MAX_VALUE);

        assertEquals(RunEngine.MAX_TIME_LIMIT_MS, l.timeLimitMs());
        assertEquals(RunEngine.MAX_MEMORY_LIMIT_MB, l.memoryLimitMb());
        assertEquals(RunEngine.MAX_OUTPUT_LIMIT_BYTES, l.outputLimitBytes());
        assertEquals(20_000L, l.compileTimeLimitMs());
    }
}
