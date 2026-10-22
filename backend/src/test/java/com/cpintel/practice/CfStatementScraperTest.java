package com.cpintel.practice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Reading a Codeforces problem page (resources/codeforces/statement-1900C.html, shaped like the
 * real markup) into a statement, and sanitising its HTML on the way.
 *
 * <p>Written to pin the parser before moving jsoup from 1.17.2 to 1.23.2: the expected values are
 * what 1.17.2 produced. 1.23.2 produced the same apart from whitespace between block elements,
 * which is why the HTML is compared with runs of whitespace folded to one space.
 */
class CfStatementScraperTest {

    private CfStatementScraper scraper;
    private String page;

    @BeforeEach
    void setUp() throws IOException {
        scraper = new CfStatementScraper(mock(CfWebFetcher.class));
        ReflectionTestUtils.setField(scraper, "webUrl", "https://codeforces.com");
        page = new String(getClass().getResourceAsStream("/codeforces/statement-1900C.html").readAllBytes(),
            StandardCharsets.UTF_8);
    }

    private static String folded(String html) {
        return html == null ? null : html.replaceAll("\\s+", " ").trim();
    }

    private PracticeDto.ProblemDetail parsed() {
        return scraper.parsePage(page, 1900, "c", 1400, List.of("math"), false);
    }

    @Test
    @DisplayName("the header gives the name without its letter, the limits without their labels, and the files")
    void header() {
        var d = parsed();
        assertEquals("1900", d.contestId());
        assertEquals("C", d.index());
        assertEquals("Pair Sums", d.name());
        assertEquals("2 seconds", d.timeLimit());
        assertEquals("256 megabytes", d.memoryLimit());
        assertEquals("standard input", d.inputFile());
        assertEquals("standard output", d.outputFile());
        assertEquals("https://codeforces.com/problemset/problem/1900/C", d.url());
        assertTrue(d.statementAvailable());
        assertNull(d.statementIssue());
    }

    @Test
    @DisplayName("the legend keeps formulas, styling, links and images, with links made absolute")
    void legend() {
        assertEquals(folded("""
            <p>You are given an array $$$a$$$ of $$$n$$$ integers and a number <span class="tex-font-style-bf">k</span>.
            Count the pairs $$$(i, j)$$$ with $$$i &lt; j$$$ and $$$a_i + a_j = k$$$.</p>
            <p>See <a href="https://codeforces.com/blog/entry/123">the editorial</a> &amp; the picture:</p>
            <img src="https://codeforces.com/espresso/abc.png" alt="pairs" style="max-width: 100%;">
            <p>Long dashes — and non-breaking&nbsp;spaces stay.</p>"""), folded(parsed().legendHtml()));
    }

    @Test
    @DisplayName("scripts, event handlers and layout tags do not survive sanitising")
    void sanitised() {
        var d = parsed();
        for (String html : List.of(d.legendHtml(), d.inputSpecHtml(), d.outputSpecHtml(), d.noteHtml())) {
            assertFalse(html.contains("<script"), html);
            assertFalse(html.contains("document.cookie"), html);
            assertFalse(html.contains("onclick"), html);
            assertFalse(html.contains("onerror"), html);
            assertFalse(html.contains("<center"), html);
        }
    }

    @Test
    @DisplayName("input, output and note sections lose their headings and keep lists and tables")
    void sections() {
        var d = parsed();
        assertEquals(folded("""
            <p>The first line contains <span class="tex-font-style-it">n</span> and $$$k$$$
            ($$$1 \\le n \\le 2 \\cdot 10^5$$$).</p><ul> <li>first</li> <li>second</li></ul>"""),
            folded(d.inputSpecHtml()).replace("> <ul>", "><ul>").replace("</li> </ul>", "</li></ul>"));
        assertEquals("<p>Print one integer.</p>", d.outputSpecHtml());
        assertEquals(folded("""
            <p>In the first test the only pair is $$$(1, 3)$$$.</p>
            <table class="bordertable"> <tbody> <tr> <td>1</td> <td>3</td> </tr> </tbody> </table>"""),
            folded(d.noteHtml()));
        assertFalse(d.inputSpecHtml().contains("section-title"));
    }

    @Test
    @DisplayName("samples are read in both of Codeforces' layouts, with entities decoded")
    void samples() {
        assertEquals(List.of(
            new PracticeDto.Sample("3 4\n1 2 3\n", "1\n"),
            new PracticeDto.Sample("5 10\n5 5 5 5 5\n", "10<&>\n")), parsed().samples());
    }

    @Test
    @DisplayName("a browser check instead of a statement is reported, not parsed")
    void browserCheck() {
        var d = scraper.parsePage("<html><title>Just a moment...</title></html>", 1900, "C", null, List.of(), false);
        assertFalse(d.statementAvailable());
        assertEquals("BROWSER_CHECK", d.statementIssue());
    }
}
