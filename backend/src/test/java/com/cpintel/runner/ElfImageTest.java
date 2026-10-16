package com.cpintel.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ElfImageTest {

    @Test
    @DisplayName("what is not an ELF, or not there, has no image size")
    void notAnElf(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("main.py");
        Files.writeString(script, "print(1)\n");
        assertEquals(-1, ElfImage.loadKb(script));
        assertEquals(-1, ElfImage.loadKb(dir.resolve("missing")));
        Path truncated = dir.resolve("truncated");
        Files.write(truncated, new byte[] {0x7f, 'E', 'L', 'F', 2, 1});
        assertEquals(-1, ElfImage.loadKb(truncated));
    }

    @Test
    @DisplayName("a real binary's image is read from its load segments")
    void realBinary() {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        if (!Files.isRegularFile(java)) return;
        long kb = ElfImage.loadKb(java);
        // The java launcher is small but certainly an ELF on Linux.
        if (System.getProperty("os.name").toLowerCase().contains("linux")) {
            assertTrue(kb > 0 && kb < 10 * 1024, "java launcher image " + kb + " KB");
        }
    }
}
