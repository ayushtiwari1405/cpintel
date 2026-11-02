package com.cpintel.questions;

import com.cpintel.entity.BankQuestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.cpintel.entity.BankQuestion.Checker.*;
import static org.junit.jupiter.api.Assertions.*;

/** The bank's comparison, which has to agree with DOMjudge's default validator. */
class OutputCheckerTest {

    private static boolean ok(String got, String want, BankQuestion.Checker c, Double tol) {
        return OutputChecker.difference(got, want, c, tol) == null;
    }

    @Test
    @DisplayName("Spacing and line breaks never matter; token counts do")
    void whitespace() {
        assertTrue(ok("1   2\n\n3\r\n", "1 2 3\n", EXACT, null));
        assertTrue(ok("", "  \n", EXACT, null));
        assertFalse(ok("1 2", "1 2 3", EXACT, null));
        assertFalse(ok("1 2 3 4", "1 2 3", EXACT, null));
    }

    @Test
    @DisplayName("Exact keeps case; ignore-case does not; numbers are text unless there is a tolerance")
    void textual() {
        assertFalse(ok("yes", "YES", EXACT, null));
        assertTrue(ok("yes", "YES", IGNORE_CASE, null));
        assertFalse(ok("1.0", "1", EXACT, null));
        assertFalse(ok("1.0", "1", IGNORE_CASE, null));
    }

    @Test
    @DisplayName("A tolerance accepts numbers within it, absolutely or relatively")
    void tolerance() {
        assertTrue(ok("1.0000005", "1", FLOAT, 1e-6));
        assertTrue(ok("1000000.5", "1000000", FLOAT, 1e-6));
        assertFalse(ok("1.01", "1", FLOAT, 1e-6));
        assertFalse(ok("abc", "1", FLOAT, 1e-6));
        assertTrue(ok("YES 0.5", "yes 0.5000001", FLOAT, 1e-6));
        assertFalse(ok("1d", "1", FLOAT, 1e-6));
        assertTrue(ok("-0.0", "0", FLOAT, 1e-9));
    }

    @Test
    @DisplayName("The difference says where and what")
    void messages() {
        assertEquals("token 2: expected \"3\", got \"4\"", OutputChecker.difference("1 4", "1 3", EXACT, null));
        assertEquals("output ended after 1 token; the answer has 2", OutputChecker.difference("1", "1 3", EXACT, null));
        assertEquals("output has 1 extra token after the answer's 1, starting \"9\"",
            OutputChecker.difference("1 9", "1", EXACT, null));
    }
}
