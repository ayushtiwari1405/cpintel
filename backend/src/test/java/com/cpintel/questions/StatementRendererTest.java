package com.cpintel.questions;

import com.cpintel.exception.ApiException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The PDF a setter's Markdown becomes, read back with PDFBox. */
class StatementRendererTest {

    private final StatementRenderer renderer = new StatementRenderer();

    private static final String BODY = """
        Given $n$ integers $a_1, a_2, \\ldots, a_n$, print $\\sum_{i=1}^{n} a_i \\bmod (10^9+7)$.

        ## Input

        The first line contains $n$ ($1 \\le n \\le 2 \\cdot 10^5$). Prices like $5 and $6 stay text.

        $$\\frac{a+b}{2} \\le \\sqrt{x_i^2 + y_i^2}$$

        | n | answer |
        |---|--------|
        | 1 | 2 |

        Code stays as written: `a_i * b_i` and

        ```
        int $x$ = 1;
        ```

        <script>alert(1)</script>

        See ![a diagram](http://127.0.0.1:1/x.png). Unicode ≤ × é.
        """;

    private StatementRenderer.Result render(String body, List<StatementRenderer.Sample> samples) {
        return renderer.render(new StatementRenderer.Statement("Sum of many", body, 1500, 256, samples));
    }

    private static String text(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    @DisplayName("Title, limits, body, tables and samples all reach the PDF; maths draws without problems")
    void renders() throws Exception {
        StatementRenderer.Result r = render(BODY,
            List.of(new StatementRenderer.Sample("3\n1 2 3\n", "6\n")));
        if (System.getenv("STATEMENT_PREVIEW_OUT") != null) {
            java.nio.file.Files.write(java.nio.file.Path.of(System.getenv("STATEMENT_PREVIEW_OUT") + ".pdf"), r.pdf());
        }

        assertEquals(List.of(), r.problems());
        assertTrue(new String(r.pdf(), 0, 5).startsWith("%PDF-"));
        String t = text(r.pdf());
        assertTrue(t.contains("Sum of many"), t);
        assertTrue(t.contains("Time limit: 1.5 seconds"), t);
        assertTrue(t.contains("Memory limit: 256 MB"), t);
        assertTrue(t.contains("Input"), t);
        assertTrue(t.contains("Prices like $5 and $6 stay text."), t);
        assertTrue(t.contains("a_i * b_i"), t);
        assertTrue(t.contains("int $x$ = 1;"), t);
        assertTrue(t.contains("answer"), t);
        assertTrue(t.contains("Example"), t);
        assertTrue(t.contains("1 2 3"), t);
        assertTrue(t.contains("≤ × é"), t);

        // Escaped, not run or fetched.
        assertTrue(t.contains("<script>alert(1)</script>"), t);
        assertTrue(t.contains("[image: a diagram]"), t);

        // A page image, for a person to look at when the layout changes.
        String out = System.getenv("STATEMENT_PREVIEW_OUT");
        if (out != null) {
            try (PDDocument doc = Loader.loadPDF(r.pdf())) {
                BufferedImage page = new PDFRenderer(doc).renderImageWithDPI(0, 110);
                ImageIO.write(page, "png", new File(out));
            }
        }
    }

    @Test
    @DisplayName("A formula that does not parse is printed as written and reported")
    void badFormula() throws Exception {
        StatementRenderer.Result r = render("Broken: $\\nosuchcommand{1}$ and fine: $x^2$.", List.of());

        assertEquals(1, r.problems().size(), r.problems().toString());
        assertTrue(text(r.pdf()).contains("$\\nosuchcommand{1}$"));
    }

    @Test
    @DisplayName("Formulas cannot define macros, which would leak into every later render")
    void macros() {
        StatementRenderer.Result r = render("$\\newcommand{\\x}{y} \\x$ and $\\def\\y{z}$", List.of());

        assertEquals(2, r.problems().size(), r.problems().toString());
        assertTrue(r.problems().get(0).startsWith("Formulas cannot define macros"));
    }

    @Test
    @DisplayName("Too many formulas are refused; an over-long one is reported")
    void limits() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i <= StatementRenderer.MAX_FORMULAS; i++) many.add("$x$");
        ApiException e = assertThrows(ApiException.class, () -> render(String.join(" ", many), List.of()));
        assertTrue(e.getMessage().contains("at most"));

        StatementRenderer.Result r = render("$" + "x+".repeat(StatementRenderer.MAX_FORMULA_CHARS) + "x$", List.of());
        assertEquals(1, r.problems().size());
    }

    @Test
    @DisplayName("Long samples are cut with a note")
    void longSample() throws Exception {
        String big = "1\n".repeat(StatementRenderer.MAX_SAMPLE_LINES + 10);
        String t = text(render("Body.", List.of(new StatementRenderer.Sample(big, "ok"))).pdf());

        assertTrue(t.contains("Shown in part"), t);
    }

    @Test
    @DisplayName("Control characters are dropped rather than breaking the document")
    void control() throws Exception {
        String t = text(render("a\u0000b\u0007c", List.of()).pdf());

        assertTrue(t.contains("abc"), t);
    }

    @Test
    @DisplayName("Limits read naturally")
    void limitText() {
        assertEquals("Time limit: 1 second  ·  Memory limit: 64 MB", StatementRenderer.limits(1000, 64));
        assertEquals("Time limit: 2 seconds  ·  Memory limit: 256 MB", StatementRenderer.limits(2000, 256));
    }
}
