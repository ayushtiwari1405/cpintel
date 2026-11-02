package com.cpintel.questions;

import com.cpintel.exception.ApiException;
import com.openhtmltopdf.extend.FSObjectDrawer;
import com.openhtmltopdf.extend.FSObjectDrawerFactory;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.scilab.forge.jlatexmath.TeXConstants;
import org.scilab.forge.jlatexmath.TeXFormula;
import org.scilab.forge.jlatexmath.TeXIcon;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.Insets;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * A question's statement as the PDF candidates read: its title and limits, the Markdown body
 * with its TeX drawn as vector formulas, and its samples.
 *
 * <p><b>Nothing is fetched.</b> The document is built from text the setter typed, so every
 * external resource (images, stylesheets, fonts by URL) is refused, and Markdown images are
 * printed as their description. Raw HTML in the Markdown is escaped, not rendered. Fonts are
 * the bundled DejaVu faces, so the PDF looks the same on every server.
 *
 * <p><b>TeX.</b> JLaTeXMath parses each formula. It keeps its macro table in static state, so a
 * formula that defines a macro would change every later render on the server, for everyone:
 * those commands are refused outright, along with formulas too long or too many. A formula that
 * does not parse is printed in red as written and listed in {@link Result#problems()}.
 *
 * <p><b>Load.</b> Rendering takes real CPU, and only setters can ask for it, so at most two run
 * at once and a third waits briefly before being told to try again.
 */
@Component
@Slf4j
public class StatementRenderer {

    static final int MAX_FORMULAS = 500;
    static final int MAX_FORMULA_CHARS = 2000;
    /** Samples are printed in full up to this much, then cut with a note. */
    static final int MAX_SAMPLE_CHARS = 4000;
    static final int MAX_SAMPLE_LINES = 60;

    private static final String MATH_TYPE = "application/x-cpintel-tex";
    private static final int PAD_X = 1;
    private static final int PAD_Y = 1;
    /**
     * Formulas are laid out and drawn in CSS pixels (a drawWithGraphics canvas of width/dotsPerPixel
     * maps 1:1 onto them). Body text is 11pt, 11 * 96/72 px, but Computer Modern at a given size
     * has much smaller letters than DejaVu Serif, so maths is set 1.3 times larger to match the
     * words around it.
     */
    private static final float MATH_SIZE = 11f * 96 / 72 * 1.3f;
    private static final Pattern MACRO_DEFINITION = Pattern.compile(
        "\\\\(newcommand|renewcommand|providecommand|def|edef|gdef|xdef|let|newenvironment|renewenvironment|DeclareMathOperator)(?![a-zA-Z])");
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    private static final String[][] FONTS = {
        {"DejaVuSerif.ttf", "DejaVu Serif", "400", "NORMAL"},
        {"DejaVuSerif-Bold.ttf", "DejaVu Serif", "700", "NORMAL"},
        {"DejaVuSerif-Italic.ttf", "DejaVu Serif", "400", "ITALIC"},
        {"DejaVuSerif-BoldItalic.ttf", "DejaVu Serif", "700", "ITALIC"},
        {"DejaVuSansMono.ttf", "DejaVu Sans Mono", "400", "NORMAL"},
        {"DejaVuSansMono-Bold.ttf", "DejaVu Sans Mono", "700", "NORMAL"},
    };

    private static final String STYLE = """
        @page { size: A4; margin: 20mm 18mm 22mm;
                @bottom-center { content: counter(page) " / " counter(pages);
                                 font-family: 'DejaVu Serif'; font-size: 9pt; color: #666; } }
        body { font-family: 'DejaVu Serif'; font-size: 11pt; line-height: 1.45; color: #111; }
        h1.title { font-size: 18pt; margin: 0 0 3pt; }
        .limits { font-size: 10pt; color: #444; padding-bottom: 6pt; margin-bottom: 12pt;
                  border-bottom: 0.6pt solid #999; }
        h1 { font-size: 15pt; } h2 { font-size: 13pt; margin: 14pt 0 4pt; }
        h3 { font-size: 11.5pt; margin: 12pt 0 3pt; }
        p { margin: 0 0 7pt; }
        code, pre { font-family: 'DejaVu Sans Mono'; font-size: 9.5pt; }
        pre { background: #f3f3f3; padding: 5pt 7pt; white-space: pre-wrap; word-wrap: break-word;
              margin: 0 0 8pt; }
        table { border-collapse: collapse; margin: 0 0 8pt; }
        td, th { border: 0.6pt solid #999; padding: 3pt 6pt; vertical-align: top; }
        blockquote { margin: 0 0 8pt 10pt; padding-left: 8pt; border-left: 2pt solid #ccc; color: #333; }
        object { display: inline-block; }
        .math-display { display: block; text-align: center; margin: 6pt 0; }
        .math-error { color: #b00020; font-family: 'DejaVu Sans Mono'; font-size: 9.5pt; }
        table.examples { width: 100%; table-layout: fixed; }
        table.examples th { text-align: left; background: #eee; font-weight: 700; }
        table.examples pre { background: none; padding: 0; margin: 0; }
        .cut { color: #666; font-size: 9pt; font-style: italic; }
        """;

    /** One sample, as text. */
    public record Sample(String input, String answer) {}

    /** What is printed. {@code markdown} is the body; the rest is laid out around it. */
    public record Statement(String title, String markdown, int timeLimitMs, int memoryLimitMb,
                            List<Sample> samples) {}

    public record Result(byte[] pdf, List<String> problems) {}

    private final Semaphore slots = new Semaphore(2);
    private final Parser parser;
    private final HtmlRenderer html;

    public StatementRenderer() {
        List<Extension> extensions = List.of(TablesExtension.create());
        parser = Parser.builder().extensions(extensions).build();
        html = HtmlRenderer.builder()
            .extensions(extensions)
            .escapeHtml(true)
            .sanitizeUrls(true)
            .nodeRendererFactory(context -> new NodeRenderer() {
                @Override
                public Set<Class<? extends Node>> getNodeTypes() {
                    return Set.of(Image.class);
                }

                @Override
                public void render(Node node) {
                    // An image would mean a fetch; print what it was meant to show instead.
                    StringBuilder alt = new StringBuilder();
                    for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                        if (child instanceof org.commonmark.node.Text t) alt.append(t.getLiteral());
                    }
                    context.getWriter().text("[image" + (alt.isEmpty() ? "" : ": " + alt) + "]");
                }
            })
            .build();
    }

    public Result render(Statement statement) {
        boolean got;
        try {
            got = slots.tryAcquire(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("Rendering was interrupted. Try again.");
        }
        if (!got) {
            throw ApiException.serviceUnavailable(
                "Statements are being rendered for other people right now. Try again in a moment.");
        }
        try {
            return renderNow(statement);
        } finally {
            slots.release();
        }
    }

    private Result renderNow(Statement s) {
        List<String> problems = new ArrayList<>();
        MathText.Lifted lifted = MathText.lift(clean(s.markdown()));
        List<TeXIcon> icons = new ArrayList<>();
        List<MathText.Formula> formulas = lifted.formulas();
        if (formulas.size() > MAX_FORMULAS) {
            throw ApiException.badRequest("A statement can have at most " + MAX_FORMULAS
                + " formulas; this one has " + formulas.size() + ".");
        }
        for (int k = 0; k < formulas.size(); k++) {
            icons.add(icon(formulas.get(k), problems));
        }

        String body = MathText.restore(html.render(parser.parse(lifted.markdown())),
            k -> mathHtml(k, formulas.get(k), icons.get(k)));

        Document doc = Jsoup.parse("<html><head><style></style></head><body>"
            + "<h1 class=\"title\"></h1><div class=\"limits\"></div><div class=\"body\"></div>"
            + "</body></html>");
        doc.selectFirst("style").appendText(STYLE);
        doc.selectFirst("h1.title").text(clean(s.title()));
        doc.selectFirst("div.limits").text(limits(s.timeLimitMs(), s.memoryLimitMb()));
        doc.selectFirst("div.body").html(body);
        if (!s.samples().isEmpty()) examples(doc.body(), s.samples());

        org.w3c.dom.Document w3c = new W3CDom().fromJsoup(doc);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            for (String[] f : FONTS) {
                String path = "/fonts/dejavu/" + f[0];
                builder.useFont(() -> font(path), f[1], Integer.parseInt(f[2]),
                    BaseRendererBuilder.FontStyle.valueOf(f[3]), true);
            }
            builder.useUriResolver((base, uri) -> null);
            builder.useExternalResourceAccessControl((uri, type) -> false,
                ExternalResourceControlPriority.RUN_BEFORE_RESOLVING_URI);
            builder.useExternalResourceAccessControl((uri, type) -> false,
                ExternalResourceControlPriority.RUN_AFTER_RESOLVING_URI);
            builder.useObjectDrawerFactory(new MathDrawers(icons));
            builder.withProducer("CPIntel");
            builder.withW3cDocument(w3c, "about:blank");
            builder.toStream(out);
            builder.run();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Statement rendering failed: {}", e.toString());
            throw ApiException.badRequest("The statement could not be turned into a PDF.");
        }
        return new Result(out.toByteArray(), List.copyOf(problems));
    }

    // ------------------------------------------------------------------ maths

    private static TeXIcon icon(MathText.Formula f, List<String> problems) {
        String tex = f.tex();
        String shown = shorten(tex);
        if (tex.length() > MAX_FORMULA_CHARS) {
            problems.add("Formula too long (over " + MAX_FORMULA_CHARS + " characters): " + shown);
            return null;
        }
        if (MACRO_DEFINITION.matcher(tex).find()) {
            problems.add("Formulas cannot define macros: " + shown);
            return null;
        }
        try {
            TeXIcon icon = new TeXFormula(tex).createTeXIcon(
                f.display() ? TeXConstants.STYLE_DISPLAY : TeXConstants.STYLE_TEXT, MATH_SIZE);
            icon.setForeground(Color.BLACK);
            // The box JLaTeXMath measures leaves out the overhang of a slanted last letter, and
            // the PDF clips the drawing to the box: without room the n of a_n is cut in half.
            icon.setInsets(new Insets(PAD_Y, 0, PAD_Y, PAD_X));
            return icon;
        } catch (RuntimeException | StackOverflowError e) {
            problems.add("Could not read the formula " + shown
                + (e.getMessage() == null ? "" : " (" + shorten(e.getMessage()) + ")"));
            return null;
        }
    }

    private static String mathHtml(int index, MathText.Formula f, TeXIcon icon) {
        if (icon == null) {
            String delim = f.display() ? "$$" : "$";
            return "<span class=\"math-error\">" + org.jsoup.nodes.Entities.escape(delim + f.tex() + delim)
                + "</span>";
        }
        // getIconWidth/Height are whole pixels with the insets in; the formula's baseline sits the
        // top inset plus its height below the top, and the box is lowered by what is left under it.
        float below = icon.getIconHeight() - PAD_Y - icon.getTrueIconHeight();
        String object = String.format(Locale.ROOT,
            "<object type=\"%s\" data-i=\"%d\" style=\"width:%dpx;height:%dpx;vertical-align:-%.2fpx\"></object>",
            MATH_TYPE, index, icon.getIconWidth(), icon.getIconHeight(), below);
        return f.display() ? "<span class=\"math-display\">" + object + "</span>" : object;
    }

    /** Draws each formula where its {@code <object>} sits, as vectors through Java2D. */
    private static final class MathDrawers implements FSObjectDrawerFactory {
        private final List<TeXIcon> icons;

        MathDrawers(List<TeXIcon> icons) {
            this.icons = icons;
        }

        @Override
        public boolean isReplacedObject(org.w3c.dom.Element e) {
            return MATH_TYPE.equals(e.getAttribute("type"));
        }

        @Override
        public FSObjectDrawer createDrawer(org.w3c.dom.Element e) {
            if (!isReplacedObject(e)) return null;
            Integer index = parseId(e.getAttribute("data-i"));
            TeXIcon icon = index == null || index < 0 || index >= icons.size() ? null : icons.get(index);
            if (icon == null) return null;
            return (el, x, y, width, height, device, ctx, dotsPerPixel) -> {
                device.drawWithGraphics((float) x, (float) y, (float) width / dotsPerPixel,
                    (float) height / dotsPerPixel, g -> icon.paintIcon(null, g, 0, 0));
                return null;
            };
        }

        private static Integer parseId(String s) {
            try {
                return Integer.valueOf(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ layout

    private static void examples(Element body, List<Sample> samples) {
        body.appendElement("h2").text(samples.size() == 1 ? "Example" : "Examples");
        Element table = body.appendElement("table").addClass("examples");
        Element head = table.appendElement("tr");
        head.appendElement("th").text("Input");
        head.appendElement("th").text("Output");
        for (Sample sample : samples) {
            Element row = table.appendElement("tr");
            cell(row, sample.input());
            cell(row, sample.answer());
        }
    }

    private static void cell(Element row, String text) {
        Element td = row.appendElement("td");
        String shown = clean(text == null ? "" : text);
        boolean cut = false;
        String[] lines = shown.split("\n", -1);
        if (lines.length > MAX_SAMPLE_LINES) {
            shown = String.join("\n", java.util.Arrays.copyOf(lines, MAX_SAMPLE_LINES));
            cut = true;
        }
        if (shown.length() > MAX_SAMPLE_CHARS) {
            shown = shown.substring(0, MAX_SAMPLE_CHARS);
            cut = true;
        }
        td.appendElement("pre").text(shown);
        if (cut) td.appendElement("div").addClass("cut").text("Shown in part; the full sample is in the arena.");
    }

    static String limits(int timeLimitMs, int memoryLimitMb) {
        String time = timeLimitMs % 1000 == 0
            ? (timeLimitMs / 1000) + (timeLimitMs == 1000 ? " second" : " seconds")
            : String.format(Locale.ROOT, "%.1f seconds", timeLimitMs / 1000.0);
        return "Time limit: " + time + "  ·  Memory limit: " + memoryLimitMb + " MB";
    }

    /** Text as XML can carry it: control characters other than tab and newlines removed. */
    static String clean(String s) {
        return s == null ? "" : CONTROL.matcher(s).replaceAll("");
    }

    private static String shorten(String s) {
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() > 80 ? one.substring(0, 77) + "…" : one;
    }

    private static InputStream font(String path) {
        InputStream in = StatementRenderer.class.getResourceAsStream(path);
        if (in == null) throw new IllegalStateException("Missing bundled font " + path);
        return in;
    }

    static String utf8(byte[] bytes) {
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }
}
