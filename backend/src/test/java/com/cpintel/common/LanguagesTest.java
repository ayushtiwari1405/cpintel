package com.cpintel.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Recognising a judge's language as one of ours.
 *
 * <p>This is the piece an examination's language restriction rests on, and it fails in a way
 * worth testing hard: a classifier that says the wrong thing does not throw, it quietly lets a
 * banned language through or hides a permitted one, and either is discovered by a room full of
 * people at the start of a paper.
 *
 * <p>Two ordering traps have caught this kind of table before and are asserted directly. "GNU
 * G++20" must be C++ and not C, and "JavaScript" must not be Java.
 */
class LanguagesTest {

    @Nested
    @DisplayName("Codeforces compiler names")
    class CodeforcesLabels {

        @ParameterizedTest(name = "{0} is {1}")
        @CsvSource({
            "'GNU G++20 13.2 (64 bit, winlibs)',    cpp",
            "'GNU G++23 14.2 (64 bit, msys2)',      cpp",
            "'GNU G++17 7.3.0',                     cpp",
            "'Clang++20 Diagnostics',               cpp",
            "'MS C++ 2017',                         cpp",
            "'GNU GCC C11 5.1.0',                   c",
            "'Python 3.13.2',                       python3",
            "'PyPy 3.10 (7.3.15, 64bit)',           python3",
            "'CPython 3.11.2',                      python3",
            "'Java 21 64bit',                       java",
            "'Kotlin 1.9.21',                       kotlin",
            "'Node.js 15.8.0 (64bit)',              javascript",
            "'C# 10, .NET SDK 6.0',                 csharp",
            "'Rust 1.75.0 (2021)',                  rust",
            "'Go 1.22.2',                           go",
            "'Ruby 3.2.2',                          ruby",
            "'Scala 2.12.8',                        scala",
            "'Haskell GHC 8.10.1',                  haskell",
            "'Delphi 7',                            pascal",
            "'PHP 8.1.7',                           php",
            "'OCaml 4.02.1',                        ocaml",
        })
        void classifiesLabels(String label, String expected) {
            assertEquals(expected, Languages.classify(null, label));
        }

        /**
         * The trap that makes the order of the table load-bearing.
         *
         * A rule for C written loosely enough to catch "GNU GCC C11" also catches "GNU G++20",
         * and a C++-only examination would then accept C.
         */
        @Test
        @DisplayName("a G++ compiler is C++, never C")
        void gppIsNotC() {
            assertEquals("cpp", Languages.classify(null, "GNU G++20 13.2 (64 bit, winlibs)"));
            assertEquals("cpp", Languages.classify(null, "GNU G++17 7.3.0"));
            assertNotEquals("c", Languages.classify(null, "GNU G++20 13.2"));
        }

        /** The other one: "JavaScript" contains "Java". */
        @Test
        @DisplayName("JavaScript is not Java")
        void javascriptIsNotJava() {
            assertEquals("javascript", Languages.classify(null, "JavaScript V8 4.8.0"));
            assertEquals("javascript", Languages.classify(null, "Node.js 15.8.0 (64bit)"));
            assertEquals("java", Languages.classify(null, "Java 21 64bit"));
        }
    }

    @Nested
    @DisplayName("DOMjudge ids")
    class JudgeIds {

        @ParameterizedTest(name = "{0} is {1}")
        @CsvSource({
            "cpp,     cpp",
            "c,       c",
            "java,    java",
            "py3,     python3",
            "python3, python3",
            "kt,      kotlin",
            "cs,      csharp",
            "js,      javascript",
            "rb,      ruby",
            "pas,     pascal",
            "hs,      haskell",
        })
        void classifiesIds(String id, String expected) {
            assertEquals(expected, Languages.classify(id, null));
        }

        /**
         * A bare "c" is why ids are matched before labels.
         *
         * As a word it matches nothing useful, and any pattern loose enough to catch it would
         * catch half the catalogue.
         */
        @Test
        @DisplayName("the id wins over the label when they disagree")
        void idWinsOverLabel() {
            // DOMjudge installations relabel freely; the id is the stable half.
            assertEquals("cpp", Languages.classify("cpp", "GNU C++ Compiler (g++)"));
            assertEquals("c", Languages.classify("c", "C"));
        }

        @Test
        @DisplayName("an id that is not exact still classifies if it reads as a language")
        void fallsBackToPatternOnTheId() {
            assertEquals("cpp", Languages.classify("gnu-c++", null));
            assertEquals("python3", Languages.classify("gnu-python", null));
        }

        @Test
        @DisplayName("case and surrounding space do not matter")
        void tolerant() {
            assertEquals("cpp", Languages.classify("  CPP  ", null));
            assertEquals("python3", Languages.classify("Py3", null));
        }
    }

    @Nested
    @DisplayName("What it refuses to guess at")
    class Unknown {

        /**
         * Null means "not allowed" wherever a restriction is in force, so guessing is the
         * expensive mistake and silence is the cheap one.
         */
        @ParameterizedTest
        @ValueSource(strings = {"brainfuck", "Whitespace 0.3", "Befunge", "zzz", "   "})
        void answersNullRatherThanGuessing(String label) {
            assertNull(Languages.classify(null, label));
            assertNull(Languages.classify(label, null));
        }

        @Test
        @DisplayName("nulls throughout are not an error")
        void nullsAreFine() {
            assertNull(Languages.classify(null, null));
            assertNull(Languages.classify("", ""));
        }
    }

    @Nested
    @DisplayName("The catalogue")
    class Catalogue {

        @Test
        @DisplayName("every id in it is recognised as known")
        void catalogueIsSelfConsistent() {
            for (Languages.Known known : Languages.CATALOG) {
                assertTrue(Languages.isKnown(known.id()), known.id() + " should be known");
                assertEquals(known.label(), Languages.labelFor(known.id()));
            }
        }

        /**
         * Every language in the catalogue must be reachable by classification.
         *
         * An entry an admin can tick but that nothing ever classifies to would be a rule that
         * silently bans everything — the exact failure the fail-closed direction makes
         * dangerous, so it is asserted rather than assumed.
         */
        @Test
        @DisplayName("every language in it can be arrived at from some judge id")
        void everyEntryIsReachable() {
            for (Languages.Known known : Languages.CATALOG) {
                assertEquals(known.id(), Languages.classify(known.id(), null),
                    "no judge id maps to " + known.id() + ", so ticking it would ban everything");
            }
        }

        @Test
        @DisplayName("the two the local runner can execute are in it")
        void runnerLanguagesArePresent() {
            // The runner's ids and these are one namespace on purpose; if they diverge, an
            // allow-list gates Submit and silently fails to gate Run.
            assertTrue(Languages.isKnown("cpp"));
            assertTrue(Languages.isKnown("python3"));
        }

        @Test
        @DisplayName("something that is not in it is not known")
        void unknownIsNotKnown() {
            assertFalse(Languages.isKnown("fortran"));
            assertFalse(Languages.isKnown(null));
            assertEquals("fortran", Languages.labelFor("fortran"));
        }
    }
}
