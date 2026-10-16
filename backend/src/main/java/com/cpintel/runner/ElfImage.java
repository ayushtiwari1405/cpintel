package com.cpintel.runner;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * How much memory a native program needs before its first instruction runs.
 *
 * <p>The commonest way to run out of memory in competitive programming is a global array that
 * is too big: {@code static int a[1 << 28]}. Under the address-space cap the kernel cannot map
 * it, and the program dies with SIGSEGV at exec, having used nothing. Its peak says nothing, and
 * the crash looks like any other; its load segments say what happened.
 */
final class ElfImage {

    private static final int PT_LOAD = 1;

    private ElfImage() {}

    /**
     * The sum of a 64-bit ELF's loadable segments in memory (code, data and bss), in KB, or -1
     * when the file is not one or cannot be read.
     */
    static long loadKb(Path file) {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer header = read(ch, 0, 64);
            if (header == null || header.getInt(0) != 0x464c457f   // \x7fELF, little-endian read
                || header.get(4) != 2 || header.get(5) != 1) {     // 64-bit, little-endian
                return -1;
            }
            long phoff = header.getLong(0x20);
            int phentsize = Short.toUnsignedInt(header.getShort(0x36));
            int phnum = Short.toUnsignedInt(header.getShort(0x38));
            if (phentsize < 0x30 || phnum == 0 || phnum > 256) return -1;

            ByteBuffer table = read(ch, phoff, phentsize * phnum);
            if (table == null) return -1;
            long bytes = 0;
            for (int i = 0; i < phnum; i++) {
                int at = i * phentsize;
                if (table.getInt(at) == PT_LOAD) bytes += table.getLong(at + 0x28);   // p_memsz
            }
            return bytes < 0 ? -1 : bytes / 1024;
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    private static ByteBuffer read(FileChannel ch, long at, int length) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        while (buf.hasRemaining()) {
            if (ch.read(buf, at + buf.position()) < 0) return null;
        }
        return buf;
    }
}
