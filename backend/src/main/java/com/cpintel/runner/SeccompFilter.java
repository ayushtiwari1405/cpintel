package com.cpintel.runner;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * The seccomp filter every run is put under, inside its bubblewrap sandbox.
 *
 * <p>The runner container already has a seccomp profile (deploy/runner-seccomp.json), but it is
 * Docker's default loosened by exactly the calls bubblewrap needs to build namespaces — mount,
 * unshare, pivot_root — and the program inherits all of it. This narrows it again for the program
 * alone: the calls below fail with EPERM. None of them is something a solution reading stdin and
 * writing stdout has any use for, and each is kernel attack surface, the way out of a sandbox
 * that namespaces and rlimits do not cover:
 *
 * <ul>
 *   <li>sockets — on top of there being no network, no unix sockets to anything either</li>
 *   <li>ptrace and reading or writing another process's memory</li>
 *   <li>bpf, perf_event_open, userfaultfd and io_uring, the usual kernel-exploit entry points</li>
 *   <li>the kernel keyring, modules, kexec, reboot, swap and accounting</li>
 *   <li>mount, unshare, setns, pivot_root and chroot — no new namespaces from inside</li>
 *   <li>open_by_handle_at, which reaches files by handle rather than by path</li>
 * </ul>
 *
 * <p>clone and clone3 stay allowed: current glibc creates every thread with clone3, and a filter
 * cannot read clone3's flags to tell a thread from a namespace. A call from another ABI on the
 * same kernel (32-bit, x32) is refused outright, so it cannot be used to reach the same calls by
 * different numbers.
 *
 * <p>Written as classic BPF by hand, because it is twenty-odd comparisons and libseccomp would be
 * a native dependency for that.
 */
final class SeccompFilter {

    private SeccompFilter() {}

    private static final int AUDIT_ARCH_X86_64 = 0xc000003e;
    private static final int AUDIT_ARCH_AARCH64 = 0xc00000b7;

    private static final int RET_ALLOW = 0x7fff0000;
    private static final int RET_EPERM = 0x00050000 | 1;

    // BPF opcodes
    private static final short LD_W_ABS = 0x20;
    private static final short JEQ_K = 0x15;
    private static final short JGE_K = 0x35;
    private static final short RET_K = 0x06;

    /** Offsets in struct seccomp_data. */
    private static final int NR = 0;
    private static final int ARCH = 4;

    /** x86_64 numbers, in the order of the list in the class comment. */
    static final int[] X86_64 = {
        41, 42, 43, 49, 50, 288,          // socket connect accept bind listen accept4
        101, 310, 311,                    // ptrace process_vm_readv process_vm_writev
        321, 298, 323, 425, 426, 427,     // bpf perf_event_open userfaultfd io_uring_*
        250, 248, 249,                    // keyctl add_key request_key
        175, 313, 176, 246, 320, 169,     // init/finit/delete_module kexec_load kexec_file_load reboot
        167, 168, 163,                    // swapon swapoff acct
        165, 166, 272, 308, 155, 161,     // mount umount2 unshare setns pivot_root chroot
        304,                              // open_by_handle_at
    };

    /** The same calls on arm64 (the generic table). */
    static final int[] AARCH64 = {
        198, 203, 202, 200, 201, 242,
        117, 270, 271,
        280, 241, 282, 425, 426, 427,
        219, 217, 218,
        105, 273, 106, 104, 294, 142,
        224, 225, 89,
        40, 39, 97, 268, 41, 51,
        265,
    };

    /** The filter for this machine, or empty where it is not an architecture known here. */
    static Optional<byte[]> forThisMachine() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "amd64", "x86_64" -> Optional.of(build(AUDIT_ARCH_X86_64, X86_64, true));
            case "aarch64", "arm64" -> Optional.of(build(AUDIT_ARCH_AARCH64, AARCH64, false));
            default -> Optional.empty();
        };
    }

    /** Written once, to a file bubblewrap is handed on a descriptor for every run. */
    static Optional<Path> writeForThisMachine() {
        return forThisMachine().map(bytes -> {
            try {
                Path file = Files.createTempFile("cpintel-seccomp-", ".bpf");
                file.toFile().deleteOnExit();
                Files.write(file, bytes);
                return file;
            } catch (IOException e) {
                throw new IllegalStateException("Could not write the seccomp filter", e);
            }
        });
    }

    static byte[] build(int auditArch, int[] denied, boolean refuseX32) {
        return layout(auditArch, denied, refuseX32);
    }

    /**
     * The program:
     * <pre>
     *   ld  [arch]
     *   jeq #auditArch, +1, +0
     *   ret #EPERM                      ; another ABI
     *   ld  [nr]
     *   jge #0x40000000 -> deny         ; x32 calls (x86_64 only)
     *   jeq #n1 -> deny ...             ; one per refused call
     *   ret #ALLOW
     * deny:
     *   ret #EPERM
     * </pre>
     */
    private static byte[] layout(int auditArch, int[] denied, boolean refuseX32) {
        int body = denied.length + (refuseX32 ? 1 : 0);
        int count = 4 + body + 2;
        ByteBuffer out = ByteBuffer.allocate(count * 8).order(ByteOrder.nativeOrder());

        put(out, LD_W_ABS, 0, 0, ARCH);
        put(out, JEQ_K, 1, 0, auditArch);
        put(out, RET_K, 0, 0, RET_EPERM);
        put(out, LD_W_ABS, 0, 0, NR);

        // Index of the first comparison is 4; "deny" is the last instruction, count - 1. A jump's
        // offset counts from the instruction after it.
        int deny = count - 1;
        int pc = 4;
        if (refuseX32) {
            put(out, JGE_K, deny - pc - 1, 0, 0x40000000);
            pc++;
        }
        for (int nr : denied) {
            put(out, JEQ_K, deny - pc - 1, 0, nr);
            pc++;
        }
        put(out, RET_K, 0, 0, RET_ALLOW);
        put(out, RET_K, 0, 0, RET_EPERM);
        return out.array();
    }

    private static void put(ByteBuffer out, short code, int jt, int jf, int k) {
        if (jt > 255 || jf > 255) throw new IllegalStateException("BPF jump out of range");
        out.putShort(code).put((byte) jt).put((byte) jf).putInt(k);
    }
}
