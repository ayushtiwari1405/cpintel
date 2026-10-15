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
}
