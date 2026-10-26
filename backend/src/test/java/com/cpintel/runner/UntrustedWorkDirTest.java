package com.cpintel.runner;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the runner reads back out of a work directory, the submitted program wrote.
 *
 * <p>A FIFO left at the name GNU time reports to, or at the binary's name, used to block the
 * runner's open forever and keep the run's slot: three such runs and Run was off for everyone.
 */
class UntrustedWorkDirTest {

    private static Path fifo(Path dir, String name) throws Exception {
        Path at = dir.resolve(name);
        Process mk;
        try {
            mk = new ProcessBuilder("mkfifo", at.toString()).start();
        } catch (java.io.IOException e) {
            Assumptions.abort("mkfifo is not available here");
            return null;
        }
        Assumptions.assumeTrue(mk.waitFor() == 0, "mkfifo failed");
        return at;
    }

    @Test
    @DisplayName("a FIFO at the peak report's name is not opened")
    void peakFifo(@TempDir Path dir) throws Exception {
        Path peak = fifo(dir, ".peak-0");
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertNull(RunEngine.readPeak(peak)));
    }

    @Test
    @DisplayName("a FIFO at the binary's name is not opened")
    void binaryFifo(@TempDir Path dir) throws Exception {
        Path binary = fifo(dir, "main");
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertEquals(-1, ElfImage.loadKb(binary)));
    }

    @Test
    @DisplayName("a link out of the work directory is not followed")
    void symlink(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("elsewhere");
        Files.writeString(target, "Command exited\n123\n");
        Path link = Files.createSymbolicLink(dir.resolve(".peak-0"), target);
        assertNull(RunEngine.readPeak(link));
    }

    @Test
    @DisplayName("an ordinary report is still read, and an oversized one is not")
    void ordinaryReport(@TempDir Path dir) throws Exception {
        Path peak = dir.resolve(".peak-0");
        Files.writeString(peak, "Command exited with non-zero status 1\n2048\n");
        assertEquals(2048L, RunEngine.readPeak(peak));

        Files.writeString(peak, "x".repeat(10_000) + "\n2048\n");
        assertNull(RunEngine.readPeak(peak));
    }
}
