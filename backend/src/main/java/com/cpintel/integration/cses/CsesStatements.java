package com.cpintel.integration.cses;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CSES task statements, read from the public task page (the API's statement endpoint answers
 * "unsupported"). The statement is cut down to safe markup and its KaTeX spans rewritten to
 * the {@code $$$...$$$} delimiters CPIntel's MathJax already typesets for Codeforces.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CsesStatements {

    private static final Duration TTL = Duration.ofHours(6);
    private static final Safelist ALLOWED = Safelist.relaxed()
        .addTags("sup", "sub", "hr")
        .removeProtocols("img", "src", "http");

    private final org.springframework.web.reactive.function.client.WebClient platformWebClient;
    private final com.cpintel.integration.OutboundRateLimiter rateLimiter;

    public record Example(String input, String output) {}

    public record Statement(String taskId, String name, String timeLimit, String memoryLimit,
                            String html, List<Example> examples) {}

    private record Cached(Statement statement, Instant at) {}

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** The statement, or null when CSES has no such task. */
    public Statement statement(String taskId) {
        Cached hit = cache.get(taskId);
        if (hit != null && hit.at().plus(TTL).isAfter(Instant.now())) return hit.statement();
        rateLimiter.acquire("cses", 500, 30_000);
        String page = platformWebClient.get().uri("https://cses.fi/problemset/task/{id}/", taskId)
            .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(20));
        Statement st = page == null ? null : parse(taskId, page);
        if (st != null && cache.size() < 1_000) cache.put(taskId, new Cached(st, Instant.now()));
        return st;
    }

    static Statement parse(String taskId, String page) {
        Document doc = Jsoup.parse(page, "https://cses.fi/");
        Element md = doc.selectFirst("div.md");
        if (md == null) return null;
        String title = doc.title().replaceFirst("^CSES - ", "").trim();

        String time = null, memory = null;
        for (Element li : doc.select("ul.task-constraints li")) {
            String text = li.text();
            if (text.startsWith("Time limit:")) time = text.substring("Time limit:".length()).trim();
            if (text.startsWith("Memory limit:")) memory = text.substring("Memory limit:".length()).trim();
        }

        for (Element m : md.select("span.math")) {
            boolean display = m.hasClass("math-display");
            m.replaceWith(new org.jsoup.nodes.TextNode(display ? "$$$$$$" + m.text() + "$$$$$$" : "$$$" + m.text() + "$$$"));
        }

        // Examples: an "Input:" paragraph followed by a pre, then "Output:" and a pre.
        List<Example> examples = new ArrayList<>();
        String pendingInput = null;
        for (Element el : md.children()) {
            if (!"pre".equals(el.tagName())) continue;
            Element label = el.previousElementSibling();
            String said = label == null ? "" : label.text().trim().toLowerCase();
            if (said.startsWith("input")) {
                pendingInput = el.wholeText();
            } else if (said.startsWith("output") && pendingInput != null) {
                examples.add(new Example(pendingInput, el.wholeText()));
                pendingInput = null;
            }
        }

        String html = Jsoup.clean(md.html(), "https://cses.fi/", ALLOWED,
            new Document.OutputSettings().prettyPrint(false));
        return new Statement(taskId, title, time, memory, html, examples);
    }
}
