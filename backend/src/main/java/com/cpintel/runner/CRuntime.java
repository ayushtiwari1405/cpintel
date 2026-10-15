package com.cpintel.runner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * C via gcc.
 *
 * <p>The same build as {@link CppRuntime}, for the same reason: what runs here should behave as
 * the submission will on the judge. GNU C11 is the dialect Codeforces calls "GNU GCC C11", and
 * the GNU extensions are on because every judge's gcc has them on.
 */
@Component
public class CRuntime implements LanguageRuntime {

    /** Where to find the compiler. Overridable for a non-standard toolchain. */
    @Value("${cpintel.runner.c.compiler:gcc}")
    private String compiler = "gcc";

    /** Language standard. Codeforces offers GNU GCC C11; DOMjudge's default gcc is gnu17. */
    @Value("${cpintel.runner.c.standard:gnu11}")
    private String standard = "gnu11";

    @Override public String id() { return "c"; }
    @Override public String displayName() { return "C (gcc)"; }
    @Override public String editorLanguage() { return "c"; }
    @Override public String sourceFileName() { return "main.c"; }

    @Override
    public boolean isAvailable() {
        return Toolchains.onPath(compiler);
    }

    @Override
    public String unavailableReason() {
        return "gcc was not found on PATH. Install it (apt install gcc) to run C locally.";
    }

    @Override
    public Optional<List<String>> compileCommand(Path workDir) {
        return Optional.of(List.of(
            compiler,
            "-std=" + standard,
            "-O2",
            "-pipe",
            "-m64",
            "-static-libgcc",
            "-o", "program",
            sourceFileName(),
            // After the source: the linker resolves left to right, and judges link libm, so a
            // solution calling sqrt() must not fail here and pass there.
            "-lm"
        ));
    }

    @Override
    public List<String> runCommand(Path workDir) {
        return List.of("./program");
    }
}
