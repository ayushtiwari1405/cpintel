package com.cpintel.runner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * C++ via g++.
 *
 * <p>Built with the flags a Codeforces-style judge uses, so behaviour here matches what a
 * submission will do there: optimised, a recent standard, and the 64-bit target CF runs.
 */
@Component
public class CppRuntime implements LanguageRuntime {

    /** Where to find the compiler. Overridable for a non-standard toolchain. */
    @Value("${cpintel.runner.cpp.compiler:g++}")
    private String compiler = "g++";

    /** Language standard. Codeforces' most-used option is GNU G++20. */
    @Value("${cpintel.runner.cpp.standard:c++20}")
    private String standard = "c++20";

    @Override public String id() { return "cpp"; }
    @Override public String displayName() { return "C++ (g++)"; }
    @Override public String editorLanguage() { return "cpp"; }
    @Override public String sourceFileName() { return "main.cpp"; }

    @Override
    public boolean isAvailable() {
        return Toolchains.onPath(compiler);
    }

    @Override
    public String unavailableReason() {
        return "g++ was not found on PATH. Install it (apt install g++) to run C++ locally.";
    }

    @Override
    public Optional<List<String>> compileCommand(Path workDir) {
        return Optional.of(List.of(
            compiler,
            "-std=" + standard,
            "-O2",
            "-pipe",
            // Matches the Codeforces 64-bit compilers: long long is 64-bit either way, but
            // this keeps int/pointer sizes identical to what a submission will see there.
            "-m64",
            // Static libstdc++/libgcc so the binary does not depend on shared libraries
            // being visible inside the sandbox's mount namespace.
            "-static-libstdc++", "-static-libgcc",
            "-o", "program",
            sourceFileName()
        ));
    }

    @Override
    public List<String> runCommand(Path workDir) {
        return List.of("./program");
    }
}
