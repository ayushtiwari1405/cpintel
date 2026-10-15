package com.cpintel.runner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs one command under resource limits and, where the host supports it, filesystem and
 * network isolation.
 *
 * <p>Two layers:
 *
 * <ul>
 *   <li><b>rlimits</b>, always. CPU seconds, address space and output file size, applied by
 *       a {@code sh} wrapper via {@code ulimit} before it execs the target.
 *   <li><b>bubblewrap</b>, when {@code bwrap} is on PATH and usable. Only {@code /usr} (plus
 *       the merged-/usr symlinks) is mounted, read-only — so {@code /home}, {@code /etc} and
 *       {@code /var} simply do not exist inside. Namespaces are unshared, which takes the
 *       network away too.
 * </ul>
 *
 * <p>The bind set is deliberately an allowlist rather than {@code --ro-bind / /}. A
 * read-only whole-root sandbox still lets the program read every secret the backend user
 * can — SSH keys, {@code .env}, the JWT signing key — and print them to stdout, which the
 * UI then displays. That is harmless when the only code running is your own on your own
 * machine, and an account-to-secrets escalation the moment the backend is shared.
 *
 * <p>Without bwrap the process still gets rlimits and a scratch directory, but nothing
 * stops it reading the filesystem. {@link #isIsolated()} reports which applies so callers
 * can say so plainly.
 */
@Component
@Slf4j
public class Sandbox {

    /** Buffer chunk when draining the child's pipes. */
    private static final int READ_CHUNK = 8 * 1024;

    /** Grace period for reader threads to finish after the process exits. */
    private static final Duration DRAIN_GRACE = Duration.ofSeconds(2);

    /** Enough for a compiler driver and its children; far short of a fork bomb. */
    private static final int MAX_PROCESSES = 64;

    private volatile Boolean bwrapUsable;

    /**
     * Whether the sandbox gets its own /proc.
     *
     * <p>Inside a container it cannot: Docker masks parts of the container's /proc, and the
     * kernel refuses a fresh proc mount over a masked one. The PID namespace is still
     * unshared either way — the program just has no /proc to read, which compilers and
     * competitive-programming solutions do not need. Decided by the probe.
     */
    private volatile boolean mountProc = true;

    /** The per-run seccomp filter ({@link SeccompFilter}), or null where it cannot be applied. */
    private volatile Path seccomp;

    public record ExecResult(
        int exitCode,
        String stdout,
        String stderr,
        long durationMs,
        boolean timedOut,
        boolean truncated
    ) {
        public boolean ok() { return exitCode == 0 && !timedOut; }
    }

    public record Limits(
        Duration wallClock,
        int memoryMb,
        boolean limitAddressSpace,
        int outputCapBytes
    ) {}

    /** True when bubblewrap is available, i.e. the run is filesystem- and network-isolated. */
    public boolean isIsolated() {
        return bwrapAvailable();
    }

    /**
     * Run {@code argv} in {@code workDir}, feeding {@code stdin} and capturing output.
     *
     * <p>Never throws for a misbehaving child: a crash, a timeout or an output flood all
     * come back as a populated {@link ExecResult}.
     */
    public ExecResult exec(List<String> argv, Path workDir, String stdin, Limits limits)
        throws IOException {

        List<String> command = wrap(argv, workDir, limits);
        ProcessBuilder pb = new ProcessBuilder(command).directory(workDir.toFile());
        // A predictable, minimal environment: inheriting the server's leaks its config, and
        // PATH is all the toolchains need.
        pb.environment().clear();
        pb.environment().put("PATH", "/usr/local/bin:/usr/bin:/bin");
        pb.environment().put("HOME", workDir.toString());
        pb.environment().put("LANG", "C.UTF-8");

        long started = System.nanoTime();
        Process process = pb.start();

        feedStdin(process, stdin);
        Drain out = drain(process.getInputStream(), limits.outputCapBytes());
        Drain err = drain(process.getErrorStream(), limits.outputCapBytes());

        boolean exited;
        try {
            exited = process.waitFor(limits.wallClock().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            exited = false;
        }

        if (!exited) {
            // The whole tree, not just the process started: a run under GNU time is a child of
            // it, and outside bubblewrap (no PID namespace to tear down) killing only the parent
            // would leave the program running on.
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            try {
                process.waitFor(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        out.join();
        err.join();
        long durationMs = (System.nanoTime() - started) / 1_000_000;

        return new ExecResult(
            exited ? process.exitValue() : -1,
            out.text(), err.text(),
            durationMs,
            !exited,
            out.truncated() || err.truncated());
    }

    // ── command construction ───────────────────────────────────────────────

    /** Wrap the target command in ulimits, and in bubblewrap when it is usable. */
    private List<String> wrap(List<String> argv, Path workDir, Limits limits) {
        // `sh -c '...' sh <argv>` passes the target as positional parameters and execs "$@",
        // so nothing in argv is ever re-parsed by the shell.
        StringBuilder ulimits = new StringBuilder();
        ulimits.append("ulimit -t ").append(cpuSecondsFor(limits)).append("; ");
        if (limits.limitAddressSpace()) {
            ulimits.append("ulimit -v ").append(limits.memoryMb() * 1024L).append("; ");
        }
        // Cap files the program can write, so it cannot fill the disk.
        ulimits.append("ulimit -f ").append(64 * 1024).append("; ");
        // Cap processes as well as CPU, memory and file size.
        //
        // Under bubblewrap the unshared PID namespace already contains a fork bomb, but the
        // documented fallback path — rlimits only, when bwrap is unavailable — had nothing
        // between submitted code and the host's process table. This is the one term that closes
        // it. Generous enough that a compiler driver spawning cc1 and as is unaffected.
        //
        // Spelled two ways: bash calls it -u, but /bin/sh on Debian and Ubuntu is dash, where
        // it is -p and -u is an error. Written only as -u, it was never applied on those
        // systems, and every run's stderr began with "ulimit: Illegal option -u".
        boolean sandboxed = bwrapAvailable();
        long processes = sandboxed ? MAX_PROCESSES : tasksOfThisUser() + MAX_PROCESSES;
        ulimits.append("if (ulimit -u ").append(processes).append(") 2>/dev/null; then ulimit -u ")
            .append(processes).append("; elif (ulimit -p ").append(processes)
            .append(") 2>/dev/null; then ulimit -p ").append(processes).append("; fi; ");
        ulimits.append("exec \"$@\"");

        List<String> inner = new ArrayList<>(List.of("/bin/sh", "-c", ulimits.toString(), "sh"));
        inner.addAll(argv);

        if (!sandboxed) return inner;

        List<String> outer = new ArrayList<>(bwrapArgs(workDir));
        outer.addAll(inner);
        return outer;
    }

    /**
     * How many processes and threads this user already has — what the process cap is counted
     * against outside bubblewrap.
     *
     * <p>{@code ulimit -u} is not "this program may start 64 processes": the kernel counts every
     * task the user owns, threads included. Inside bubblewrap's fresh user namespace that count
     * starts near zero, so a flat 64 works. Without it, the count includes this JVM's own threads
     * and everything else the account runs — on a CI runner that is already past 64, so gcc could
     * not even start cc1. The cap is therefore set at what exists now plus the allowance.
     *
     * <p>Read from /proc where there is one (Linux, which counts threads); elsewhere processes
     * are what is counted, and ProcessHandle can see those.
     */
    private static long tasksOfThisUser() {
        Path proc = Path.of("/proc");
        if (Files.isDirectory(proc.resolve("self"))) {
            try {
                String uid = uidLine(proc.resolve("self/status"));
                long tasks = 0;
                try (var entries = Files.newDirectoryStream(proc, "[0-9]*")) {
                    for (Path entry : entries) {
                        try {
                            List<String> status = Files.readAllLines(entry.resolve("status"));
                            if (!status.contains(uid)) continue;
                            for (String line : status) {
                                if (line.startsWith("Threads:")) {
                                    tasks += Long.parseLong(line.substring(8).trim());
                                }
                            }
                        } catch (IOException | NumberFormatException e) {
                            // Exited while being read, or not ours to read: not counted.
                        }
                    }
                }
                return tasks;
            } catch (IOException e) {
                // Fall through to the portable count.
            }
        }
        var user = ProcessHandle.current().info().user();
        return ProcessHandle.allProcesses()
            .filter(p -> user.isPresent() && p.info().user().equals(user))
            .count();
    }

    /** The "Uid:" line of a /proc status file, compared whole so uid 10 is not uid 100. */
    private static String uidLine(Path status) throws IOException {
        for (String line : Files.readAllLines(status)) {
            if (line.startsWith("Uid:")) return line;
        }
        throw new IOException("No Uid line in " + status);
    }

    /**
     * The bubblewrap invocation, up to and including the {@code --} separator.
     *
     * <p>Built in one place and used by both {@link #wrap} and {@link #probeBwrap()}. They
     * were separate lists once and drifted: the probe omitted the /lib symlinks, so the
     * dynamic loader was missing inside it, every probe failed, and the runner silently fell
     * back to running unsandboxed while the real wrap would have worked fine. A probe that
     * does not test the real configuration is worse than no probe.
     */
    private List<String> bwrapArgs(Path workDir) {
        List<String> args = new ArrayList<>();
        Path filter = seccomp;
        if (filter != null) {
            // bubblewrap takes the filter on an open descriptor, which ProcessBuilder cannot hand
            // over; the shell opens it as fd 9 and becomes bwrap. "$0" is the filter's path. Nine,
            // not ten: /bin/sh on Debian and Ubuntu is dash, which redirects single digits only.
            args.addAll(List.of("/bin/sh", "-c", "exec \"$@\" 9<\"$0\"", filter.toString()));
        }
        args.add("bwrap");
        if (filter != null) args.addAll(List.of("--seccomp", "9"));
        args.addAll(List.of(
            "--ro-bind", "/usr", "/usr",
            // Ubuntu and friends have merged /usr; these keep absolute paths like
            // /bin/sh and the dynamic loader in /lib64 resolving, without exposing
            // anything beyond /usr.
            "--symlink", "usr/bin", "/bin",
            "--symlink", "usr/lib", "/lib",
            "--symlink", "usr/lib64", "/lib64",
            "--symlink", "usr/sbin", "/sbin"
        ));
        if (mountProc) args.addAll(List.of("--proc", "/proc"));
        args.addAll(List.of(
            "--dev", "/dev",
            "--tmpfs", "/tmp"
        ));
        // g++ resolves through /etc/alternatives on Debian-family systems.
        if (Files.isDirectory(Path.of("/etc/alternatives"))) {
            args.addAll(List.of("--ro-bind", "/etc/alternatives", "/etc/alternatives"));
        }
        args.addAll(List.of(
            "--bind", workDir.toString(), workDir.toString(),
            "--chdir", workDir.toString(),
            // Everything: network, pid, ipc, uts, cgroup and user namespaces.
            "--unshare-all",
            "--die-with-parent",
            "--new-session",
            "--"
        ));
        return args;
    }

    /**
     * CPU limit, one second above the wall clock.
     *
     * <p>The wall clock is the real deadline. Keeping the CPU limit just above it means a
     * genuinely spinning program is still killed by SIGXCPU if the wall-clock kill somehow
     * misses, without a program that merely sleeps being reported as a CPU overrun.
     */
    private long cpuSecondsFor(Limits limits) {
        return Math.max(1, limits.wallClock().toSeconds() + 1);
    }

    /** Probe bwrap once: present on PATH, and actually able to create namespaces here. */
    private boolean bwrapAvailable() {
        Boolean cached = bwrapUsable;
        if (cached != null) return cached;

        synchronized (this) {
            if (bwrapUsable != null) return bwrapUsable;
            seccomp = SeccompFilter.writeForThisMachine().orElse(null);
            boolean usable = probeBothWays();
            if (!usable && seccomp != null) {
                // The namespaces may still work where the filter does not; better a sandbox
                // without it than none, but nobody should find that out by accident.
                seccomp = null;
                usable = probeBothWays();
                if (usable) {
                    log.error("bubblewrap works here but not with the per-run seccomp filter; "
                        + "runs are isolated without it. Check that the kernel has "
                        + "CONFIG_SECCOMP_FILTER and that bwrap supports --seccomp.");
                }
            }
            if (!usable) {
                log.warn("bubblewrap unavailable — code runs with resource limits but WITHOUT "
                    + "filesystem or network isolation. Keep cpintel.runner.enabled off on any "
                    + "shared deployment.");
            } else {
                log.info("bubblewrap available — runner is filesystem and network isolated{}{}.",
                    seccomp != null ? ", with a per-run seccomp filter" : "",
                    mountProc ? "" : " (no /proc inside the sandbox)");
            }
            bwrapUsable = usable;
            return usable;
        }
    }

    /** With its own /proc first, as on a desktop; without, as inside a container. */
    private boolean probeBothWays() {
        mountProc = true;
        if (probeBwrap()) return true;
        mountProc = false;
        return probeBwrap();
    }

    private boolean probeBwrap() {
        Path probeDir = null;
        try {
            probeDir = Files.createTempDirectory("cpintel-bwrap-probe-");
            List<String> cmd = new ArrayList<>(bwrapArgs(probeDir));
            cmd.addAll(List.of("/bin/sh", "-c", "exit 0"));

            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean ok = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
            if (!ok && !output.isBlank()) {
                log.debug("bwrap probe failed: {}", output.strip());
            }
            return ok;
        } catch (IOException e) {
            return false;   // not installed
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (probeDir != null) {
                try { Files.deleteIfExists(probeDir); } catch (IOException ignored) { /* best effort */ }
            }
        }
    }

    // ── stream plumbing ────────────────────────────────────────────────────

    /**
     * Write stdin on its own thread and close it.
     *
     * <p>On a thread because a program that never reads its input leaves the pipe full, and
     * writing inline would block us before we ever reach waitFor.
     */
    private void feedStdin(Process process, String stdin) {
        Thread t = new Thread(() -> {
            try (OutputStream os = process.getOutputStream()) {
                if (stdin != null && !stdin.isEmpty()) {
                    os.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // The program exited before reading its input. Normal; nothing to do.
            }
        }, "runner-stdin");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Drain a pipe on its own thread, keeping at most {@code cap} bytes.
     *
     * <p>Reading has to continue past the cap even though the excess is discarded: stopping
     * would fill the pipe and block the child forever instead of letting it finish.
     */
    private Drain drain(InputStream stream, int cap) {
        Drain drain = new Drain(cap);
        Thread t = new Thread(() -> drain.pump(stream), "runner-drain");
        t.setDaemon(true);
        drain.thread = t;
        t.start();
        return drain;
    }

    private static final class Drain {
        private final int cap;
        private final java.io.ByteArrayOutputStream kept = new java.io.ByteArrayOutputStream();
        private final AtomicBoolean overflowed = new AtomicBoolean(false);
        private Thread thread;

        Drain(int cap) { this.cap = cap; }

        void pump(InputStream stream) {
            byte[] buf = new byte[READ_CHUNK];
            try (InputStream in = stream) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    int room = cap - kept.size();
                    if (room > 0) {
                        kept.write(buf, 0, Math.min(n, room));
                    }
                    if (n > room) overflowed.set(true);
                }
            } catch (IOException ignored) {
                // Pipe closed under us when the process was killed. Keep what we have.
            }
        }

        void join() {
            try {
                if (thread != null) thread.join(DRAIN_GRACE.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        String text() { return kept.toString(StandardCharsets.UTF_8); }
        boolean truncated() { return overflowed.get(); }
    }
}
