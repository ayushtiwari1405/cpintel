package com.cpintel.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The per-run seccomp filter: built correctly, and actually in force on a real run. The live
 * tests need bubblewrap and gcc; without them (a CI runner has no bubblewrap) they skip.
 */
class SeccompFilterTest {

    @Test
    @DisplayName("every jump lands inside the program, and it ends allow then deny")
    void wellFormed() {
        byte[] bpf = SeccompFilter.build(0xc000003e, SeccompFilter.X86_64, true);
        ByteBuffer b = ByteBuffer.wrap(bpf).order(ByteOrder.nativeOrder());
        int count = bpf.length / 8;
        for (int i = 0; i < count; i++) {
            short code = b.getShort(i * 8);
            int jt = b.get(i * 8 + 2) & 0xff;
            // Index 1 is the architecture check, which skips the "another ABI" refusal instead.
            if (i >= 4 && (code == 0x15 || code == 0x35)) {
                assertEquals(count - 1, i + 1 + jt, "jump at " + i + " should land on deny");
            }
        }
        assertEquals(0x7fff0000, b.getInt((count - 2) * 8 + 4));
        assertEquals(0x00050001, b.getInt((count - 1) * 8 + 4));
        assertEquals(SeccompFilter.X86_64.length, SeccompFilter.AARCH64.length,
            "both architectures refuse the same calls");
    }

    static boolean liveSandbox() {
        return new Sandbox().isIsolated() && new CRuntime().isAvailable()
            && SeccompFilter.forThisMachine().isPresent();
    }

    private static RunDto.TestResult runC(String source) {
        RunEngine engine = new RunEngine(List.of(new CRuntime()), new Sandbox(),
            new RunEngine.Limits(10_000L, 20_000L, 512, 65_536), false);
        RunDto.RunResponse r = engine.run("c", source,
            List.of(new RunDto.TestCase("", null, "t")));
        assertTrue(r.compiled(), r.compileOutput());
        return r.results().get(0);
    }

    @Test
    @EnabledIf("liveSandbox")
    @DisplayName("a run cannot open a socket or trace a process")
    void refusedCalls() {
        RunDto.TestResult r = runC("""
            #include <errno.h>
            #include <stdio.h>
            #include <sys/ptrace.h>
            #include <sys/socket.h>
            int main(void) {
                int s = socket(AF_UNIX, SOCK_STREAM, 0);
                printf("socket %d %d\\n", s, s < 0 ? errno : 0);
                long t = ptrace(PTRACE_TRACEME, 0, 0, 0);
                printf("ptrace %ld %d\\n", t, t < 0 ? errno : 0);
                return 0;
            }
            """);
        assertEquals("socket -1 1\nptrace -1 1\n", r.actual(), r.stderr());
    }

    @Test
    @EnabledIf("liveSandbox")
    @DisplayName("ordinary work, threads included, is untouched")
    void ordinaryWorkRuns() {
        RunDto.TestResult r = runC("""
            #include <pthread.h>
            #include <stdio.h>
            #include <stdlib.h>
            static void *work(void *arg) { *(int *) arg *= 2; return NULL; }
            int main(void) {
                int x = 21; pthread_t t;
                pthread_create(&t, NULL, work, &x);
                pthread_join(t, NULL);
                int *big = malloc(64 << 20); big[0] = x;
                printf("%d\\n", big[0]);
                return 0;
            }
            """);
        assertEquals("42\n", r.actual(), r.stderr());
    }
}
