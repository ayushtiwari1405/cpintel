package com.cpintel.practice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the shape of Codeforces' /data/submitSource response.
 *
 * The payloads below follow a real response field for field — Codeforces flattens the per-test
 * data into the top-level object as input#1, answer#1 and so on, reports booleans and numbers
 * as strings, and serves CRLF throughout. Those are exactly the details a rewrite would get
 * wrong, and getting them wrong shows up as an empty results panel rather than as an error,
 * which is why this is a test and not a comment.
 */
class CfSubmissionParseTest {

    /** A wrong answer with two tests, the second failing — the case the panel exists for. */
    private static final String WRONG_ANSWER = """
        {
          "source": "#include <bits/stdc++.h>\\r\\nint main(){}\\r\\n",
          "compilationError": "false",
          "testCount": "2",
          "verdict": "<span class='verdict-rejected'>Wrong answer on test 2</span>",
          "input#1": "3\\r\\n1 3 5\\r\\n",
          "output#1": "1\\r\\n3\\r\\n5\\r\\n",
          "answer#1": "1\\r\\n3\\r\\n5\\r\\n",
          "verdict#1": "OK",
          "checkerStdoutAndStderr#1": "ok 3 numbers\\r\\n",
          "exitCode#1": "0",
          "timeConsumed#1": "15",
          "memoryConsumed#1": "0",
          "input#2": "2\\r\\n7 9\\r\\n",
          "output#2": "8\\r\\n10\\r\\n",
          "answer#2": "7\\r\\n9\\r\\n",
          "verdict#2": "WRONG_ANSWER",
          "checkerStdoutAndStderr#2": "wrong answer 1st numbers differ\\r\\n",
          "exitCode#2": "0",
          "timeConsumed#2": "0",
          "memoryConsumed#2": "61440"
        }
        """;

    @Nested
    @DisplayName("A judged submission")
    class Judged {

        @Test
        @DisplayName("reads every test, in order")
        void readsTests() throws Exception {
            CfWebSubmitClient.SubmissionDetail d =
                CfWebSubmitClient.parseSubmissionDetail(WRONG_ANSWER);

            assertNotNull(d);
            assertEquals(2, d.testCount());
            assertEquals(2, d.tests().size());
            assertEquals(1, d.tests().get(0).index());
            assertEquals(2, d.tests().get(1).index());
        }

        @Test
        @DisplayName("keeps input, output and expected apart")
        void keepsPayloadsApart() throws Exception {
            CfWebSubmitClient.CfTest failing =
                CfWebSubmitClient.parseSubmissionDetail(WRONG_ANSWER).tests().get(1);

            assertEquals("WRONG_ANSWER", failing.verdict());
            assertEquals("2\n7 9\n", failing.input());
            assertEquals("8\n10\n", failing.output(), "This is what the program printed.");
            assertEquals("7\n9\n", failing.answer(), "This is what it should have printed.");
            assertTrue(failing.checkerMessage().startsWith("wrong answer"));
        }

        @Test
        @DisplayName("CRLF is normalised — the panel renders it verbatim")
        void normalisesNewlines() throws Exception {
            CfWebSubmitClient.SubmissionDetail d =
                CfWebSubmitClient.parseSubmissionDetail(WRONG_ANSWER);

            assertFalse(d.source().contains("\r"));
            for (CfWebSubmitClient.CfTest t : d.tests()) {
                assertFalse(t.input().contains("\r"), "test " + t.index() + " input");
                assertFalse(t.output().contains("\r"), "test " + t.index() + " output");
                assertFalse(t.answer().contains("\r"), "test " + t.index() + " answer");
            }
        }

        @Test
        @DisplayName("numbers arrive as strings and are still read as numbers")
        void parsesStringNumbers() throws Exception {
            CfWebSubmitClient.CfTest first =
                CfWebSubmitClient.parseSubmissionDetail(WRONG_ANSWER).tests().get(0);

            assertEquals(15, first.timeMs());
            assertEquals(0L, first.memoryBytes());
            assertEquals(0, first.exitCode());
        }

        @Test
    @DisplayName("compilationError arrives as the literal false, meaning no error")
        void treatsFalseAsNoError() throws Exception {
            assertNull(CfWebSubmitClient.parseSubmissionDetail(WRONG_ANSWER).compilationError());
        }
    }

    @Test
    @DisplayName("A compilation error is surfaced, with no tests to show")
    void readsCompilationError() throws Exception {
        String json = """
            {
              "source": "int main(){ syntax error }\\r\\n",
              "compilationError": "program.cpp:1:13: error: expected ';'\\r\\n",
              "testCount": "0"
            }
            """;
        CfWebSubmitClient.SubmissionDetail d = CfWebSubmitClient.parseSubmissionDetail(json);

        assertNotNull(d.compilationError());
        assertTrue(d.compilationError().contains("expected ';'"));
        assertFalse(d.compilationError().contains("\r"));
        assertTrue(d.tests().isEmpty());
    }

    @Test
    @DisplayName("Mid-contest: a test count with no data behind it yields no tests, not blanks")
    void handlesWithheldTestData() throws Exception {
        // What a running round looks like: Codeforces names the failing test in the verdict
        // markup and ships none of the per-test fields. Inventing empty rows here would put a
        // table of dashes in front of the user instead of an explanation.
        String json = """
            {
              "source": "int main(){}\\r\\n",
              "compilationError": "false",
              "testCount": "4",
              "verdict": "<span class='verdict-rejected'>Wrong answer on test 4</span>"
            }
            """;
        CfWebSubmitClient.SubmissionDetail d = CfWebSubmitClient.parseSubmissionDetail(json);

        assertEquals(4, d.testCount(), "The count is still reported.");
        assertTrue(d.tests().isEmpty(), "But there is nothing behind it.");
    }

    @Test
    @DisplayName("Clipped test data is flagged, so nobody debugs against half an input")
    void flagsTruncation() throws Exception {
        String big = "9".repeat(511) + "...";
        String json = """
            {
              "source": "int main(){}\\n",
              "testCount": "1",
              "input#1": "%s",
              "output#1": "1\\n",
              "answer#1": "2\\n",
              "verdict#1": "WRONG_ANSWER"
            }
            """.formatted(big);

        List<CfWebSubmitClient.CfTest> tests =
            CfWebSubmitClient.parseSubmissionDetail(json).tests();
        assertTrue(tests.get(0).truncated(),
            "Codeforces clips around 500 chars and marks it with an ellipsis.");
    }

    @Test
    @DisplayName("Short data is not flagged as clipped")
    void doesNotOverFlagTruncation() throws Exception {
        String json = """
            {
              "source": "int main(){}\\n",
              "testCount": "1",
              "input#1": "3\\n1 2 3\\n",
              "output#1": "6\\n",
              "answer#1": "6\\n",
              "verdict#1": "OK"
            }
            """;
        assertFalse(CfWebSubmitClient.parseSubmissionDetail(json).tests().get(0).truncated());
    }

    @Test
    @DisplayName("A response with no source is refused rather than half-read")
    void refusesSourcelessResponse() throws Exception {
        assertNull(CfWebSubmitClient.parseSubmissionDetail("{\"testCount\":\"3\"}"));
    }
}
