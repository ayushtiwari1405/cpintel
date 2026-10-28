package com.cpintel.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A program owns its work directory while it runs, so it can chmod parts of it shut. The
 * cleanup used to give up on such a tree and leave it on the runner's shared /tmp until a
 * restart; a few runs could fill it and stop Run for every candidate.
 */
class WorkDirCleanupTest {

    @TempDir Path tmp;

    @Test
    @DisplayName("a subdirectory the program closed with chmod 000 is still removed")
    void closedSubdirectory() throws Exception {
        Path work = Files.createDirectory(tmp.resolve("cpintel-run-1"));
        Path keep = Files.createDirectories(work.resolve("keep/deeper"));
        Files.write(keep.resolve("blob"), new byte[4096]);
        Files.setPosixFilePermissions(keep, PosixFilePermissions.fromString("---------"));
        Files.setPosixFilePermissions(work.resolve("keep"), PosixFilePermissions.fromString("---------"));

        RunEngine.deleteQuietly(work);

        assertFalse(Files.exists(work));
    }

    @Test
    @DisplayName("the work directory itself closed with chmod 000 is still removed")
    void closedWorkDirectory() throws Exception {
        Path work = Files.createDirectory(tmp.resolve("cpintel-run-2"));
        Files.write(work.resolve("out"), new byte[16]);
        Files.setPosixFilePermissions(work, PosixFilePermissions.fromString("---------"));

        RunEngine.deleteQuietly(work);

        assertFalse(Files.exists(work));
    }

    @Test
    @DisplayName("a link out of the work directory is removed, not followed")
    void linkNotFollowed() throws Exception {
        Path outside = Files.createDirectory(tmp.resolve("outside"));
        Files.write(outside.resolve("precious"), new byte[1]);
        Files.setPosixFilePermissions(outside, PosixFilePermissions.fromString("r-x------"));
        Path work = Files.createDirectory(tmp.resolve("cpintel-run-3"));
        Files.createSymbolicLink(work.resolve("link"), outside);

        RunEngine.deleteQuietly(work);

        assertFalse(Files.exists(work));
        assertTrue(Files.exists(outside.resolve("precious")));
        // Not opened up either: the chmod applies to the work tree's own directories only.
        assertEquals("r-x------", PosixFilePermissions.toString(Files.getPosixFilePermissions(outside)));
    }

    @Test
    @DisplayName("leftover work directories are swept at start, other files are not")
    void sweep() throws Exception {
        Path left = Files.createDirectory(tmp.resolve("cpintel-run-9"));
        Files.setPosixFilePermissions(left, PosixFilePermissions.fromString("---------"));
        Path other = Files.write(tmp.resolve("unrelated"), new byte[1]);

        RunEngine.sweepLeftovers(tmp);

        assertFalse(Files.exists(left));
        assertTrue(Files.exists(other));
    }
}
