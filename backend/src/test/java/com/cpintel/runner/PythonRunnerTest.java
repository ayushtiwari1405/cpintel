package com.cpintel.runner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Python, actually compiled and run.
 *
 * <p>Not a unit test of the command strings — those can be right while the thing does not work,
 * which is the failure mode that matters for a runtime. These drive the real
 * {@link RunEngine} against the real interpreter and assert on verdicts.
 *
 * <p>Skipped where python3 is not installed, rather than failing: the runner reports a missing
 * toolchain as an unavailable language by design, and a build machine without Python is a
 * deployment choice rather than a regression.
 */
@EnabledIf("pythonInstalled")
class PythonRunnerTest {

    /**
     * Probed against PATH directly rather than through a hand-built {@link PythonRuntime}.
     *
     * Constructing one here leaves its {@code @Value} interpreter null, because nothing is
     * injecting it — so {@code isAvailable()} would answer false on a machine that has Python,
     * and this whole class would skip while reporting success. The setup below sets the field
     * explicitly for the same reason.
     */
    static boolean pythonInstalled() {
        PythonRuntime probe = new PythonRuntime();
        ReflectionTestUtils.setField(probe, "interpreter", "python3");
        return probe.isAvailable();
    }

    private RunEngine runner;

    @BeforeEach
    void setUp() {
        runner = engine(10_000L, 512);
    }

    private static RunEngine engine(long timeLimitMs, int memoryLimitMb) {
        PythonRuntime python = new PythonRuntime();
        ReflectionTestUtils.setField(python, "interpreter", "python3");
        return new RunEngine(List.of(python), new Sandbox(),
            new RunEngine.Limits(timeLimitMs, 20_000L, memoryLimitMb, 65_536), false);
    }

    private RunDto.RunResponse run(String source, String input, String expected) {
        return runner.run("python3", source,
            List.of(new RunDto.TestCase(input, expected, "Test 1")));
    }

    @Test
    @DisplayName("a correct solution reads stdin and passes")
    void correctSolutionPasses() {
        RunDto.RunResponse result = run(
            "import sys\nprint(sum(int(x) for x in sys.stdin.read().split()))\n",
            "3 4 5\n", "12\n");

        assertTrue(result.compiled(), result.compileOutput());
        assertEquals(RunDto.Verdict.OK, result.results().get(0).verdict());
        assertEquals("12", result.results().get(0).actual().strip());
    }

    @Test
    @DisplayName("a wrong answer is a wrong answer, not an error")
    void wrongAnswer() {
        RunDto.RunResponse result = run("print(41)\n", "", "42\n");

        assertTrue(result.compiled());
        assertEquals(RunDto.Verdict.WRONG_ANSWER, result.results().get(0).verdict());
    }

    /**
     * The reason this runtime has a compile step at all.
     *
     * Without {@code py_compile}, a syntax error would be reported as an identical runtime
     * error on every one of a problem's samples, with the real message buried in twelve copies
     * of the same traceback and nothing saying "this does not parse".
     */
    @Test
    @DisplayName("a syntax error is reported once, as a compile failure")
    void syntaxErrorIsACompileFailure() {
        RunDto.RunResponse result = run("def f(:\n    pass\n", "", "");

        assertFalse(result.compiled());
        assertTrue(result.compileOutput().contains("SyntaxError"), result.compileOutput());
        assertTrue(result.results() == null || result.results().isEmpty(),
            "nothing should have been run against a file that does not parse");
    }

    @Test
    @DisplayName("an exception at run time is a runtime error on that test")
    void runtimeErrorIsPerTest() {
        RunDto.RunResponse result = run("print(1 // 0)\n", "", "0\n");

        // It parsed, so the build succeeded; it threw, so the test failed.
        assertTrue(result.compiled());
        assertEquals(RunDto.Verdict.RUNTIME_ERROR, result.results().get(0).verdict());
        assertTrue(result.results().get(0).stderr().contains("ZeroDivisionError"));
    }

    @Test
    @DisplayName("an endless loop is stopped by the wall clock")
    void infiniteLoopTimesOut() {
        runner = engine(2_000L, 512);

        RunDto.RunResponse result = run("while True:\n    pass\n", "", "");

        assertEquals(RunDto.Verdict.TIME_LIMIT_EXCEEDED, result.results().get(0).verdict());
    }

    /**
     * The memory cap is on, unlike what {@link LanguageRuntime#limitAddressSpace} warns
     * interpreters may need.
     *
     * Measured rather than assumed: CPython starts and runs fine under it, and without it a
     * one-line program takes a gigabyte of the host. See {@link PythonRuntime}.
     */
    @Test
    @DisplayName("a runaway allocation is bounded rather than taking the host's memory")
    void memoryIsCapped() {
        runner = engine(10_000L, 256);

        RunDto.RunResponse result = run(
            "x = bytearray(2 * 1024 * 1024 * 1024)\nprint(len(x))\n", "", "");

        assertTrue(result.compiled());
        assertEquals(RunDto.Verdict.RUNTIME_ERROR, result.results().get(0).verdict());
        assertTrue(result.results().get(0).stderr().contains("MemoryError"),
            result.results().get(0).stderr());
    }

    @Test
    @DisplayName("an ordinary solution still fits inside the cap")
    void realWorkFitsUnderTheCap() {
        runner = engine(10_000L, 512);

        RunDto.RunResponse result = run(
            "a = list(range(2_000_000))\nprint(sum(a) % 1000)\n", "", "0\n");

        assertEquals(RunDto.Verdict.OK, result.results().get(0).verdict(),
            result.results().get(0).stderr());
    }

    @Test
    @DisplayName("the runtime announces itself as Python 3")
    void describesItself() {
        List<RunDto.RuntimeInfo> languages = runner.languages();

        assertEquals(1, languages.size());
        assertEquals("python3", languages.get(0).id());
        assertEquals("python", languages.get(0).editorLanguage());
        assertTrue(languages.get(0).available());
    }
}
