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

    /**
     * The case the error message cannot cover: C does not complain when malloc fails, it hands
     * back a null pointer and the program dies writing to it. The run's peak memory says why.
     */
    @Test
    @DisplayName("a C program that grows until malloc fails is a memory limit, not a crash")
    void silentOutOfMemoryIsMemoryLimit() {
        runner = new RunEngine(List.of(new CRuntime()), new Sandbox(),
            new RunEngine.Limits(10_000L, 20_000L, 128, 65_536), false);

        RunDto.RunResponse result = run("""
            #include <stdlib.h>
            #include <string.h>
            int main(void) {
                for (;;) {
                    char *p = malloc(1 << 20);
                    memset(p, 1, 1 << 20);
                }
            }
            """, "", "");

        assertTrue(result.compiled(), result.compileOutput());
        RunDto.TestResult r = result.results().get(0);
        assertEquals(RunDto.Verdict.MEMORY_LIMIT_EXCEEDED, r.verdict(),
            "peak " + r.peakMemoryKb() + " KB, exit " + r.exitCode());
    }

    /**
     * The commonest memory limit of all: a global array too big for the cap. The kernel cannot
     * map it, so the program dies at exec having used nothing, and its peak cannot say why.
     */
    @Test
    @DisplayName("a global array bigger than the cap is a memory limit, not a crash")
    void oversizedGlobalArrayIsMemoryLimit() {
        runner = new RunEngine(List.of(new CRuntime()), new Sandbox(),
            new RunEngine.Limits(10_000L, 20_000L, 128, 65_536), false);

        RunDto.RunResponse result = run("""
            #include <stdio.h>
            static char a[256 << 20];
            int main(void) {
                for (long i = 0; i < (long) sizeof a; i += 4096) a[i] = 1;
                printf("%d\\n", a[4096]);
                return 0;
            }
            """, "", "1\n");

        assertTrue(result.compiled(), result.compileOutput());
        RunDto.TestResult r = result.results().get(0);
        assertEquals(RunDto.Verdict.MEMORY_LIMIT_EXCEEDED, r.verdict(),
            "peak " + r.peakMemoryKb() + " KB, exit " + r.exitCode());
    }

    /** And a plain crash with a small image stays a runtime error. */
    @Test
    @DisplayName("a null-pointer crash is still a runtime error")
    void nullPointerIsRuntimeError() {
        RunDto.RunResponse result = run("""
            #include <stdio.h>
            int main(void) {
                volatile int *p = 0;
                *p = 1;
                return 0;
            }
            """, "", "");

        assertTrue(result.compiled(), result.compileOutput());
        assertEquals(RunDto.Verdict.RUNTIME_ERROR, result.results().get(0).verdict());
    }

    @Test
    @DisplayName("every run reports its peak memory")
    void reportsPeakMemory() {
        RunDto.RunResponse result = run("int main(void) { return 0; }\n", "", null);
        assertNotNull(result.results().get(0).peakMemoryKb());
    }
}
