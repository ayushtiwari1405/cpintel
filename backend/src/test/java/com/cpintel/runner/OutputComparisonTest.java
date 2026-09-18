package com.cpintel.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Output comparison, pinned.
 *
 * This is the part of the runner most likely to be wrong in a way that quietly matters: a
 * comparison that is too strict cries wolf on a correct solution, and one that is too loose
 * passes a solution Codeforces will reject — which is exactly the failure the whole feature
 * exists to prevent.
 */
class OutputComparisonTest {

    @Test
    @DisplayName("accepts output that matches exactly")
    void exactMatch() {
        assertTrue(CodeRunnerService.matches("6\n", "6\n"));
    }

    @Test
    @DisplayName("ignores a missing or extra trailing newline")
    void trailingNewline() {
        assertTrue(CodeRunnerService.matches("6", "6\n"));
        assertTrue(CodeRunnerService.matches("6\n", "6"));
        assertTrue(CodeRunnerService.matches("6\n\n\n", "6"));
    }

    @Test
    @DisplayName("ignores trailing spaces on a line")
    void trailingSpaces() {
        // Printing a space after every element is the single most common CP output habit.
        assertTrue(CodeRunnerService.matches("1 2 3 \n", "1 2 3\n"));
        assertTrue(CodeRunnerService.matches("YES\t\n", "YES\n"));
    }

    @Test
    @DisplayName("normalises CRLF, so a Windows-authored answer still matches")
    void windowsLineEndings() {
        assertTrue(CodeRunnerService.matches("1\r\n2\r\n", "1\n2\n"));
    }

    @Test
    @DisplayName("rejects a different value")
    void differentValue() {
        assertFalse(CodeRunnerService.matches("7\n", "6\n"));
    }

    @Test
    @DisplayName("rejects the right tokens laid out on the wrong lines")
    void lineStructureMatters() {
        // A token-based comparison would call this correct. It is not: a problem asking for
        // one number per line is not satisfied by one line of numbers, and letting this pass
        // would send a wrong answer to Codeforces with the runner's blessing.
        assertFalse(CodeRunnerService.matches("1 2 3\n", "1\n2\n3\n"));
    }

    @Test
    @DisplayName("rejects a missing line even when every other line matches")
    void missingLine() {
        assertFalse(CodeRunnerService.matches("1\n2\n", "1\n2\n3\n"));
    }

    @Test
    @DisplayName("rejects leading whitespace, which is a real difference")
    void leadingWhitespaceIsSignificant() {
        assertFalse(CodeRunnerService.matches("  6\n", "6\n"));
    }

    @Test
    @DisplayName("treats blank output and empty expectation as equal")
    void bothEmpty() {
        assertTrue(CodeRunnerService.matches("", ""));
        assertTrue(CodeRunnerService.matches("\n\n", ""));
    }

    @Test
    @DisplayName("rejects output when nothing was printed but something was expected")
    void printedNothing() {
        assertFalse(CodeRunnerService.matches("", "6\n"));
    }

    @Test
    @DisplayName("handles a null actual without throwing")
    void nullSafe() {
        assertFalse(CodeRunnerService.matches(null, "6"));
        assertTrue(CodeRunnerService.matches(null, ""));
    }

    @Test
    @DisplayName("preserves interior blank lines, which can be part of the answer")
    void interiorBlankLines() {
        assertFalse(CodeRunnerService.matches("a\nb\n", "a\n\nb\n"));
    }
}
