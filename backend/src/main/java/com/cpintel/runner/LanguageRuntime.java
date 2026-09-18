package com.cpintel.runner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * One language the runner can build and execute.
 *
 * <p>The contract is the competitive-programming one: a program reads a test case from
 * stdin and writes its answer to stdout. That covers C++, Python and Java directly. SQL
 * does not fit it as-is — the natural shape there is "stdin is the schema and data, the
 * user's file is the query" — so a SQL runtime will want {@link #runCommand} to be
 * something like {@code sh -c 'cat > data.sql; sqlite3 -batch :memory: ".read data.sql"
 * ".read main.sql"'}. The seam is here; the semantics need a decision when you get to it.
 *
 * <p>Implementations are Spring beans; {@link CodeRunnerService} picks between them by
 * {@link #id()}, so adding a language is adding one class and nothing else.
 */
public interface LanguageRuntime {

    /** Stable key the frontend sends, e.g. {@code "cpp"}. */
    String id();

    /** Shown in the editor's language picker. */
    String displayName();

    /** Monaco's language id, so the editor highlights correctly. */
    String editorLanguage();

    /** File the source is written to inside the work directory. */
    String sourceFileName();

    /**
     * Whether the toolchain this runtime needs is actually installed. Checked per request
     * rather than cached at startup, so installing a compiler does not need a restart.
     */
    boolean isAvailable();

    /** Human-readable reason {@link #isAvailable()} is false. */
    String unavailableReason();

    /**
     * Write whatever files the language needs before compiling. The default writes the
     * source to {@link #sourceFileName()}, which is all C++ and Python need; Java overrides
     * it if the public class has to match the filename.
     */
    default void prepare(Path workDir, String source) throws IOException {
        java.nio.file.Files.writeString(workDir.resolve(sourceFileName()), source);
    }

    /** Command that builds the program, or empty for interpreted languages. */
    Optional<List<String>> compileCommand(Path workDir);

    /** Command that runs the prepared program, reading stdin and writing stdout. */
    List<String> runCommand(Path workDir);

    /**
     * Whether to cap address space with {@code ulimit -v}.
     *
     * <p>True for compiled native code. A JVM or a Python interpreter reserves far more
     * virtual address space than it ever commits, so an address-space cap makes them fail
     * to start rather than limiting them — those runtimes should return false and be held
     * to the wall-clock timeout instead.
     */
    default boolean limitAddressSpace() {
        return true;
    }
}
