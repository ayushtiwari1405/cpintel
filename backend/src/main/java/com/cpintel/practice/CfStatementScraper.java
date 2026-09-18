package com.cpintel.practice;

import com.cpintel.exception.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pulls a problem statement off the Codeforces website.
 *
 * The public CF API exposes problem *metadata* (name, rating, tags) but not the statement
 * text or the sample tests, so those have to come from the rendered page. Results are cached
 * in-process for a day since statements never change once a contest is over.
 *
 * Math in CF statements is written as $$$...$$$ and rendered client-side by MathJax. We pass
 * it through untouched; the frontend loads MathJax and typesets it.
 */
@Component
@Slf4j
public class CfStatementScraper {

    private static final Duration TTL = Duration.ofHours(24);
    /**
     * Fallback User-Agent, used only when no session supplies one.
     *
     * <p>Cloudflare binds the {@code cf_clearance} cookie to the User-Agent that solved its
     * challenge, so replaying a session under this string instead of the browser's own gets the
     * interstitial rather than the page. The session's real User-Agent is what works.
     */
    private static final String DEFAULT_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/124.0 Safari/537.36";

    private static String uaOr(String userAgent) {
        return userAgent == null || userAgent.isBlank() ? DEFAULT_UA : userAgent;
    }

    private record Entry(PracticeDto.ProblemDetail detail, Instant at) {}

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    /** Pages are fetched outside the JVM; see {@link CfWebFetcher} for why. */
    private final CfWebFetcher fetcher;

    public CfStatementScraper(CfWebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Value("${cpintel.platforms.codeforces.web-url:https://codeforces.com}")
    private String webUrl;

    /** Safelist that keeps CF's structural markup (math spans, images, tables) but drops scripts. */
    private static Safelist safelist() {
        return Safelist.relaxed()
            .addTags("section", "span", "div", "sup", "sub", "pre", "code", "br", "hr")
            .addAttributes(":all", "class", "style")
            .addAttributes("img", "src", "alt", "width", "height")
            .addProtocols("img", "src", "http", "https");
    }

    public PracticeDto.ProblemDetail fetch(int contestId, String index,
                                           Integer rating, List<String> tags) {
        return fetch(contestId, index, rating, tags, null, null);
    }

    /**
     * A problemset statement, sent with the user's Codeforces cookies when they have a session
     * connected.
     *
     * <p>Public problemset pages need no session to read, and this used to fetch them
     * anonymously for that reason. Codeforces now serves its HTML from behind a Cloudflare
     * interstitial, which answers an ordinary HTTP client with 403 "Just a moment..." whatever
     * headers it sends -- passing it means executing the challenge's JavaScript, which no
     * server-side scrape can do. The user's own browser has already passed it, and the cookie
     * that records this ({@code cf_clearance}) travels with the session the helper collects. So
     * sending the session is what gets the statement, and the reason is nothing to do with
     * authentication.
     *
     * <p>The JSON API is not challenged, which is why the problemset cache and every rating
     * lookup keep working while statements do not.
     *
     * <p>Anonymous results are cached separately from session-backed ones: a 403 fetched
     * without cookies must not be served back to a user whose session could have read it.
     */
    public PracticeDto.ProblemDetail fetch(int contestId, String index,
                                           Integer rating, List<String> tags,
                                           String cookieHeader, String userAgent) {
        boolean hasSession = cookieHeader != null && !cookieHeader.isBlank();
        String key = (hasSession ? "session:" : "anon:") + contestId + "/" + index.toUpperCase();
        Entry cached = cache.get(key);
        if (cached != null && Instant.now().isBefore(cached.at().plus(TTL))) {
            return cached.detail();
        }

        String url = problemUrl(contestId, index);

        // Fetched by curl, parsed by jsoup. Jsoup opens its own JVM socket, and Cloudflare
        // refuses the JVM's TLS fingerprint whatever headers ride on it; the parsing was never
        // the problem, only the transport. See CfWebFetcher.
        CfWebFetcher.Response res = fetcher.get(url, hasSession ? cookieHeader : null,
            uaOr(userAgent), java.util.Map.of());

        String issue = issueFor(res, hasSession);
        if (issue != null) {
            log.warn("Could not fetch CF statement {} (session={}): {} [{}]",
                url, hasSession, res.status(), issue);
            // Degrade gracefully — the picker metadata is still useful, and the UI shows a
            // link out to Codeforces along with what went wrong.
            return new PracticeDto.ProblemDetail(
                String.valueOf(contestId), index, contestId + index, rating,
                tags, null, null, null, null, null, null, null, null,
                List.of(), url, false, null, issue);
        }
        Document doc = Jsoup.parse(res.body(), url);

        PracticeDto.ProblemDetail detail = parse(doc, contestId, index, rating, tags, url);
        cache.put(key, new Entry(detail, Instant.now()));
        return detail;
    }

    /**
     * Same parsing, but against /contest/{id}/problem/{index} and carrying the user's session.
     *
     * A running contest's statements are not public — Codeforces serves them only to an
     * account that is registered and has started — so this variant must send the cookie. The
     * cache key is kept separate from the problemset one so a failed anonymous fetch can never
     * mask a statement the contest session can actually see.
     *
     * <p>It also carries {@code cf_clearance} past the Cloudflare interstitial, the same way
     * {@link #fetch(int, String, Integer, List, String)} does.
     */
    public PracticeDto.ProblemDetail fetchForContest(int contestId, String index,
                                                     String cookieHeader, String userAgent) {
        String key = "contest:" + contestId + "/" + index.toUpperCase();
        Entry cached = cache.get(key);
        if (cached != null && Instant.now().isBefore(cached.at().plus(TTL))) {
            return cached.detail();
        }

        String url = contestProblemUrl(contestId, index);

        boolean hasSession = cookieHeader != null && !cookieHeader.isBlank();
        CfWebFetcher.Response res = fetcher.get(url, cookieHeader, uaOr(userAgent),
            java.util.Map.of());

        String issue = issueFor(res, hasSession);
        if (issue != null) {
            log.warn("Could not fetch CF contest statement {}: {} [{}]",
                url, res.status(), issue);
            return new PracticeDto.ProblemDetail(
                String.valueOf(contestId), index.toUpperCase(),
                contestId + index.toUpperCase(), null, List.of(),
                null, null, null, null, null, null, null, null, List.of(), url, false, null,
                issue);
        }
        Document doc = Jsoup.parse(res.body(), url);

        PracticeDto.ProblemDetail detail = parse(doc, contestId, index, null, List.of(), url);
        cache.put(key, new Entry(detail, Instant.now()));
        return detail;
    }

    public String contestProblemUrl(int contestId, String index) {
        return webUrl + "/contest/" + contestId + "/problem/" + index.toUpperCase();
    }

    private PracticeDto.ProblemDetail parse(Document doc, int contestId, String index,
                                            Integer rating, List<String> tags, String url) {
        Element stmt = doc.selectFirst("div.problem-statement");
        if (stmt == null) {
            throw ApiException.notFound("Problem " + contestId + index + " has no readable statement");
        }

        Element header = stmt.selectFirst("div.header");
        String name        = textOf(header, "div.title");
        String timeLimit   = limitOf(header, "div.time-limit");
        String memoryLimit = limitOf(header, "div.memory-limit");
        String inputFile   = limitOf(header, "div.input-file");
        String outputFile  = limitOf(header, "div.output-file");

        // Strip the "A. Problem Name" prefix CF puts in the title div.
        if (name != null && name.length() > 3 && name.charAt(1) == '.') {
            name = name.substring(2).trim();
        }

        String legend       = cleanLegend(stmt, url);
        String inputSpec    = cleanSection(stmt, url, "div.input-specification");
        String outputSpec   = cleanSection(stmt, url, "div.output-specification");
        String note         = cleanSection(stmt, url, "div.note");
        List<PracticeDto.Sample> samples = parseSamples(stmt);

        PracticeDto.ProblemDetail detail = new PracticeDto.ProblemDetail(
            String.valueOf(contestId), index.toUpperCase(),
            name != null && !name.isBlank() ? name : contestId + index,
            rating, tags, timeLimit, memoryLimit, inputFile, outputFile,
            legend, inputSpec, outputSpec, note, samples, url, true, null, null);

        return detail;
    }

    public String problemUrl(int contestId, String index) {
        // Problems from contests below ~100000 live under /problemset/problem/...; gym and
        // recent-division problems resolve fine through the same path.
        return webUrl + "/problemset/problem/" + contestId + "/" + index.toUpperCase();
    }

    // ------------------------------------------------------------- internals

    /**
     * What went wrong with a fetch, or {@code null} when the response is a real page.
     *
     * <p>Every failure used to arrive at the UI as the same sentence, which guessed at
     * "gym-only, or rate-limiting". Measured against the live site, neither is what usually
     * happens: Codeforces answers any client that has not passed its browser check with the
     * interstitial, at 403 on the main host and at 200 on the mirrors. A stored session carries
     * the {@code cf_clearance} that passed that check in the user's own browser, and that cookie
     * expires in hours while the session it rides in lives for a fortnight. So the common case
     * is a session that is still on file and no longer clears the check — which the user can fix
     * in a minute by reconnecting, if they are told that is the problem.
     *
     * <p>A challenge page is recognised by its body rather than its status, because the status
     * differs per host and a 200 challenge is still not a statement.
     */
    private static String issueFor(CfWebFetcher.Response res, boolean hasSession) {
        String body = res.body() == null ? "" : res.body();

        // A page carrying the statement is a page, whatever else is in it — checked first, and
        // first for a reason. Codeforces embeds Cloudflare's Turnstile script on its ordinary
        // pages, so the string "challenges.cloudflare.com" appears in perfectly good HTML:
        // measured, a genuine 2264B problem page is 150 KB, has div.problem-statement, and
        // contains that string. Sniffing for it alone condemns every successful fetch. What the
        // page *is* settles this; what it mentions cannot.
        if (res.status() == 200 && body.contains("problem-statement")) return null;

        if (looksLikeChallenge(body)) return hasSession ? "SESSION_STALE" : "NO_SESSION";
        if (res.status() == 404) return "NOT_FOUND";
        if (res.status() != 200) return "UNAVAILABLE";

        // 200, no statement, no challenge: a real answer to a problem that is not published
        // here — gym, or a contest whose statements are not out. Degrading beats throwing,
        // because the metadata and the link out are still worth showing.
        return "NOT_FOUND";
    }

    /**
     * Cloudflare's interstitial, or Codeforces' own browser check on the mirrors.
     *
     * <p>Every marker here belongs to a challenge page and to nothing else. Notably absent is
     * the Turnstile script URL, which ordinary Codeforces pages also carry.
     */
    private static boolean looksLikeChallenge(String body) {
        if (body == null || body.isEmpty()) return false;
        return body.contains("Just a moment")
            || body.contains("cf-browser-verification")
            || body.contains("cf_chl_opt")
            || body.contains("__cf_chl_")
            || body.contains("Your browser is being checked");
    }

    private List<PracticeDto.Sample> parseSamples(Element stmt) {
        List<PracticeDto.Sample> out = new ArrayList<>();
        Elements tests = stmt.select("div.sample-tests div.sample-test");
        for (Element test : tests) {
            Elements inputs  = test.select("div.input");
            Elements outputs = test.select("div.output");
            int n = Math.max(inputs.size(), outputs.size());
            for (int i = 0; i < n; i++) {
                String in  = i < inputs.size()  ? preText(inputs.get(i))  : "";
                String ex  = i < outputs.size() ? preText(outputs.get(i)) : "";
                out.add(new PracticeDto.Sample(in, ex));
            }
        }
        return out;
    }

    /**
     * Sample blocks come in two shapes: a plain {@code <pre>} with newlines, and the newer
     * {@code <pre>} full of {@code <div class="test-example-line">} rows. Handle both.
     */
    private String preText(Element block) {
        Element pre = block.selectFirst("pre");
        if (pre == null) return "";

        Elements lines = pre.select("div.test-example-line");
        if (!lines.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Element line : lines) sb.append(line.text()).append('\n');
            return sb.toString();
        }

        String html = pre.html()
            .replaceAll("(?i)<br\\s*/?>", "\n")
            .replaceAll("(?i)</div>\\s*<div[^>]*>", "\n")
            .replaceAll("(?i)<[^>]+>", "");
        return org.jsoup.parser.Parser.unescapeEntities(html, false).strip() + "\n";
    }

    private String cleanSection(Element stmt, String baseUri, String selector) {
        Element el = stmt.selectFirst(selector);
        if (el == null) return null;
        Element copy = el.clone();
        copy.select("div.section-title").remove();   // drop the "Input"/"Output" heading
        return clean(copy.html(), baseUri);
    }

    /**
     * The problem body is the one direct child of div.problem-statement that carries no
     * class attribute — it sits between div.header and div.input-specification. It has to be
     * found by walking the children rather than by selector, because a selector evaluated
     * against stmt searches stmt's descendants, not stmt itself.
     */
    private String cleanLegend(Element stmt, String baseUri) {
        for (Element child : stmt.children()) {
            if (!"div".equals(child.tagName())) continue;
            if (child.className().isBlank()) {
                return clean(child.html(), baseUri);
            }
        }
        return null;
    }

    private String clean(String html, String baseUri) {
        if (html == null || html.isBlank()) return null;
        // baseUri resolves CF's relative image paths (/predownloaded/...) to absolute URLs.
        return Jsoup.clean(html, baseUri, safelist());
    }

    private String textOf(Element parent, String selector) {
        if (parent == null) return null;
        Element el = parent.selectFirst(selector);
        return el == null ? null : el.text().trim();
    }

    /** Limit divs read "time limit per test2 seconds" — drop the leading title span. */
    private String limitOf(Element parent, String selector) {
        if (parent == null) return null;
        Element el = parent.selectFirst(selector);
        if (el == null) return null;
        Element copy = el.clone();
        copy.select("div.property-title").remove();
        String text = copy.text().trim();
        return text.isEmpty() ? null : text;
    }
}
