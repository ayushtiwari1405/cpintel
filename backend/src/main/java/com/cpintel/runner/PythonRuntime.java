package com.cpintel.runner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Python 3 via CPython.
 *
 * <p><b>There is a compile step, and it is the point of this class being more than three
 * lines.</b> Python is interpreted, so the obvious implementation returns
 * {@code Optional.empty()} from {@link #compileCommand} and lets every test fail. That is a bad
 * experience for the one mistake beginners make constantly: a syntax error would then produce
 * an identical {@code RUNTIME_ERROR} on all twelve samples, with the real message buried in
 * twelve copies of the same traceback, and nothing saying "this does not parse".
 *
 * <p>So the build step is {@code py_compile}, which parses the file and does nothing else. A
 * syntax error becomes a compilation failure reported once, with the caret and the line number,
 * exactly as a g++ error is — which is also what the judges this rehearses for do. A program
 * that parses and then raises at run time still fails per test, as it should.
 *
 * <p><b>Isolated mode ({@code -I}) on both commands.</b> It ignores {@code PYTHONPATH}, the
 * user site-packages directory and {@code PYTHON*} environment variables. Under bubblewrap
 * none of those are reachable anyway, but the sandbox has a documented fallback — rlimits only,
 * when {@code bwrap} is unusable — and on that path an unisolated interpreter would import
 * whatever is in the backend user's {@code ~/.local/lib}. It also makes a run here reproducible
 * rather than dependent on what the host happens to have installed.
 *
 * <p>One consequence worth stating: {@code -I} implies {@code -P}, so the script's own
 * directory is not put on {@code sys.path}. A solution that imported a second local file would
 * not find it — but the runner writes exactly one file, and competitive-programming solutions
 * are single-file by construction, so nothing real is lost.
 */
@Component
public class PythonRuntime implements LanguageRuntime {

    /** Where to find the interpreter. Overridable for a pyenv or a non-standard install. */
    @Value("${cpintel.runner.python.interpreter:python3}")
    private String interpreter;

    @Override public String id() { return "python3"; }
    @Override public String displayName() { return "Python 3"; }
    @Override public String editorLanguage() { return "python"; }
    @Override public String sourceFileName() { return "main.py"; }

    @Override
    public boolean isAvailable() {
        return Toolchains.onPath(interpreter);
    }

    @Override
    public String unavailableReason() {
        return "python3 was not found on PATH. Install it (apt install python3) to run Python "
            + "locally.";
    }

    /**
     * A parse, not a build.
     *
     * {@code py_compile} writes a {@code .pyc} into {@code __pycache__} beside the source and
     * exits non-zero with the usual {@code SyntaxError} on stderr. The byte-code is incidental —
     * the run below re-reads the source either way — and the work directory is a temporary one
     * that is deleted after the run, so nothing is left behind.
     */
    @Override
    public Optional<List<String>> compileCommand(Path workDir) {
        return Optional.of(List.of(interpreter, "-I", "-m", "py_compile", sourceFileName()));
    }

    @Override
    public List<String> runCommand(Path workDir) {
        return List.of(interpreter, "-I", sourceFileName());
    }

    /**
     * Address space is capped, like C++.
     *
     * <p>{@link LanguageRuntime#limitAddressSpace} warns that an interpreter may reserve far
     * more virtual address space than it commits, and that a cap can then stop it starting at
     * all. That is true of a JVM and was measured not to be true of CPython here: a trivial
     * program peaks at about 20 MB of virtual address space, starts cleanly under a 256 MB cap,
     * and a two-million-element list plus a two-million-element array fits inside the 512 MB
     * default with room to spare.
     *
     * <p>Since it costs nothing, keeping it on is worth a great deal. Without it a Python
     * solution with a runaway allocation takes memory from the host until the kernel's
     * allocator refuses — measured: a one-line program quietly took a gigabyte — whereas with
     * it the same program gets a {@code MemoryError} and is reported as a runtime error on that
     * test, which is both bounded and what a judge would have told them.
     */
    @Override
    public boolean limitAddressSpace() {
        return true;
    }

}
