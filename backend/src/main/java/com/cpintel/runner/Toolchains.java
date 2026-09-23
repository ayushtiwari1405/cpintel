package com.cpintel.runner;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * How every runtime asks whether its toolchain is installed.
 *
 * <p>One helper rather than one per runtime, so that "is the compiler there" cannot come to
 * mean two different things — the answer decides whether a language appears in the editor's
 * picker at all, and a runtime that probed differently would be offered and then fail.
 *
 * <p>Probed per request rather than cached, so installing a compiler on the host does not need
 * a restart to become usable. It is a handful of {@code stat} calls against PATH.
 */
final class Toolchains {

    private Toolchains() {}

    /** True when {@code name} resolves to an executable on PATH, or is an absolute path to one. */
    static boolean onPath(String name) {
        if (name == null || name.isBlank()) return false;
        if (name.contains("/")) return Files.isExecutable(Path.of(name));
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(":")) {
            if (dir.isBlank()) continue;
            if (Files.isExecutable(Path.of(dir, name))) return true;
        }
        return false;
    }
}
