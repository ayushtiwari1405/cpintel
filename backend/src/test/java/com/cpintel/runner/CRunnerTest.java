package com.cpintel.runner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C, actually compiled and run — see {@link PythonRunnerTest} for why it drives the real
 * {@link RunEngine} rather than checking command strings. Skipped where gcc is not installed.
 */
@EnabledIf("gccInstalled")
class CRunnerTest {

    static boolean gccInstalled() {
        return new CRuntime().isAvailable();
    }

    private RunEngine runner;

    @BeforeEach
    void setUp() {
        runner = new RunEngine(List.of(new CRuntime()), new Sandbox(),
            new RunEngine.Limits(10_000L, 20_000L, 512, 65_536), false);
    }

    private RunDto.RunResponse run(String source, String input, String expected) {
        return runner.run("c", source, List.of(new RunDto.TestCase(input, expected, "Test 1")));
    }

    @Test
    @DisplayName("a correct solution reads stdin and passes")
    void correctSolutionPasses() {
        RunDto.RunResponse result = run("""
            #include <stdio.h>
            int main(void) {
                long long a, b, c;
                scanf("%lld %lld %lld", &a, &b, &c);
                printf("%lld\\n", a + b + c);
                return 0;
            }
            """, "3 4 5\n", "12\n");

        assertTrue(result.compiled(), result.compileOutput());
        assertEquals(RunDto.Verdict.OK, result.results().get(0).verdict());
    }

    /** Judges link libm; a solution calling sqrt() must not fail to link only here. */
    @Test
    @DisplayName("the maths library is linked")
    void linksLibm() {
        RunDto.RunResponse result = run("""
            #include <math.h>
            #include <stdio.h>
            int main(void) {
                double x;
                scanf("%lf", &x);
                printf("%.0f\\n", sqrt(x));
                return 0;
            }
            """, "144\n", "12\n");

        assertTrue(result.compiled(), result.compileOutput());
        assertEquals(RunDto.Verdict.OK, result.results().get(0).verdict());
    }

    /** Compiled as C, not C++: this is valid C and a compile error in C++. */
    @Test
    @DisplayName("the source is compiled as C")
    void compiledAsC() {
        RunDto.RunResponse result = run("""
            #include <stdio.h>
            #include <stdlib.h>
            int main(void) {
                int *new = malloc(sizeof *new);
                *new = 7;
                printf("%d\\n", *new);
                return 0;
            }
            """, "", "7\n");

        assertTrue(result.compiled(), result.compileOutput());
        assertEquals(RunDto.Verdict.OK, result.results().get(0).verdict());
    }

    /**
     * Without bubblewrap the process cap is counted against everything this user runs, not
     * just the build. A flat cap of 64 was already exhausted on a CI runner, and gcc failed with
     * "cannot execute cc1: vfork: Resource temporarily unavailable" before compiling anything.
     */
    @Test
    @DisplayName("compiles without bubblewrap, where the process cap counts the whole user")
    void compilesWithoutBubblewrap() {
        Sandbox sandbox = new Sandbox();
        ReflectionTestUtils.setField(sandbox, "bwrapUsable", false);
        runner = new RunEngine(List.of(new CRuntime()), sandbox,
            new RunEngine.Limits(10_000L, 20_000L, 512, 65_536), false);

        RunDto.RunResponse result = run("#include <stdio.h>\nint main(void) { puts(\"1\"); }\n",
            "", "1\n");

        assertTrue(result.compiled(), result.compileOutput());
        assertEquals(RunDto.Verdict.OK, result.results().get(0).verdict());
    }

    @Test
    @DisplayName("a compile error is reported as one")
    void compileError() {
        RunDto.RunResponse result = run("int main(void) { return x; }\n", "", "");

        assertFalse(result.compiled());
        assertTrue(result.compileOutput().contains("main.c"), result.compileOutput());
    }
}
