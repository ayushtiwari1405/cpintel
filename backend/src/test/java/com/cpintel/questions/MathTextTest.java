package com.cpintel.questions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Which dollars are maths, by pandoc's rules, and what Markdown is left to see. */
class MathTextTest {

    private static List<String> tex(String source) {
        return MathText.lift(source).formulas().stream().map(MathText.Formula::tex).toList();
    }

    @Test
    @DisplayName("Inline and display formulas are lifted; underscores no longer reach Markdown")
    void lifts() {
        MathText.Lifted l = MathText.lift("Let $a_i$ be.\n\n$$\\sum_i a_i$$\n");

        assertEquals(List.of("a_i", "\\sum_i a_i"), tex("Let $a_i$ be.\n\n$$\\sum_i a_i$$\n"));
        assertFalse(l.markdown().contains("_"));
        assertTrue(l.formulas().get(1).display());
    }

    @Test
    @DisplayName("Money, spaced dollars and escaped dollars stay text")
    void notMath() {
        assertEquals(List.of(), tex("costs $5 and $6"));
        assertEquals(List.of(), tex("a $ b $ c"));
        assertEquals(List.of(), tex("\\$x\\$"));
        assertEquals(List.of(), tex("one $x\nacross lines$"));
    }

    @Test
    @DisplayName("Code spans and fenced blocks are left exactly as written")
    void code() {
        String src = "`$a$` and ``x `$b$` y``\n```\n$c$\n```\nafter $d$";

        assertEquals(List.of("d"), tex(src));
        assertTrue(MathText.lift(src).markdown().startsWith("`$a$` and ``x `$b$` y``\n```\n$c$\n```\n"));
    }

    @Test
    @DisplayName("A display formula may span lines; an unclosed one stays text")
    void display() {
        assertEquals(List.of("a\n+b"), tex("$$\na\n+b\n$$"));
        assertEquals(List.of(), tex("$$ never closed"));
    }

    @Test
    @DisplayName("Placeholder characters typed by the author are removed, so they cannot pose as formulas")
    void spoofed() {
        String src = "x" + MathText.OPEN + "0" + MathText.CLOSE + " $y$";
        MathText.Lifted l = MathText.lift(src);

        assertEquals(List.of("y"), tex(src));
        assertEquals("[y] x0", MathText.restore(l.markdown(), i -> "[" + l.formulas().get(i).tex() + "]")
            .replaceFirst("^x0 (.*)$", "$1 x0"));
    }
}
