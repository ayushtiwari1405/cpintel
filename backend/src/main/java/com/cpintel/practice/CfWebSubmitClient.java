package com.cpintel.practice;

import com.cpintel.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Talks to the Codeforces website using a session the user already established in their own
 * browser.
 *
 * Codeforces publishes no submission API — /api is read-only — so submitting means posting
 * the same multipart form a browser posts. What this class deliberately does *not* do is log
 * in: it never sees a password, and it never creates a session. It borrows one.
 *
 * Practical consequence: sessions die. Codeforces will expire them, and the user logging out
 * in their browser kills them immediately. Every path here surfaces CF_SESSION_INVALID rather
 * than failing obscurely, so the UI can tell the user to reconnect.
 */
@Component
@Slf4j
public class CfWebSubmitClient {

    /**
     * Fallback User-Agent, used only for sessions stored before the browser's own was carried.
     *
     * <p>It is a fallback rather than the value because Cloudflare binds {@code cf_clearance} to
     * the User-Agent that solved its challenge. A hardcoded string here matches whichever
     * browser happened to be current when it was written and no other, so it goes stale
     * silently: every affected user is told their cookies "are not logged in to Codeforces",
     * which is both wrong and unactionable. The real User-Agent travels with the session.
     */
    private static final String DEFAULT_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/124.0 Safari/537.36";

    /** The session's own User-Agent, or the fallback when it predates them being stored. */
    private static String uaOr(String userAgent) {
        return userAgent == null || userAgent.isBlank() ? DEFAULT_UA : userAgent;
    }
    private static final String ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String HEX = "0123456789abcdef";
    private static final ObjectMapper JSON = new ObjectMapper();
    /** CSRF tokens are per session and long-lived; this only avoids refetching a page. */
    private static final Duration CSRF_TTL = Duration.ofMinutes(10);

    /** Codeforces clips shown test data around this length. */
    private static final int CF_TEST_DATA_CAP = 500;
    /** Codeforces never returns anywhere near this many; it only bounds a malformed reply. */
    private static final int MAX_TESTS = 400;

    private record CsrfEntry(String token, Instant at) {}

    /**
     * One test as Codeforces reports it. Any field may be null: during a running contest it
     * names the failing test but withholds the data behind it.
     */
    public record CfTest(
        int index,
        String verdict,
        String input,
        String output,
        String answer,
        String checkerMessage,
        Integer exitCode,
        Integer timeMs,
        Long memoryBytes,
        boolean truncated
    ) {}

    /** Everything one submission page would have shown, without opening it. */
    public record SubmissionDetail(
        String source,
        String compilationError,
        int testCount,
        List<CfTest> tests
    ) {}

    private final Map<String, CsrfEntry> csrfCache = new ConcurrentHashMap<>();

    private final SecureRandom random = new SecureRandom();

    /**
     * Codeforces' website is fetched by {@link CfWebFetcher}, not from the JVM.
     *
     * <p>Cloudflare fingerprints the TLS handshake, and Java's is refused: every request from
     * java.net.http came back 403 with the "Just a moment..." interstitial, on both HTTP
     * versions and with any header set, while curl and Python got 200 with the identical
     * cookies and User-Agent. Headers are not what is being judged, so no amount of them fixes
     * it. The JSON API is not challenged and is unaffected.
     */
    private final CfWebFetcher fetcher;

    public CfWebSubmitClient(CfWebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Value("${cpintel.platforms.codeforces.web-url:https://codeforces.com}")
    private String webUrl;

    @Value("${cpintel.practice.submit-enabled:true}")
    private boolean submitEnabled;

    // ------------------------------------------------------------ public API

    /**
     * Confirms the cookies belong to a logged-in Codeforces account and returns the handle.
     * Returns null if the session is not logged in.
     */
    public String resolveHandle(String cookieHeader, String userAgent) {
        String home = get(cookieHeader, userAgent, webUrl + "/");
        return loggedInHandle(home);
    }

    /**
     * Posts source to a problem and returns the new submission id so the caller can poll the
     * public API for a verdict.
     */
    public long submit(String cookieHeader, String userAgent, int contestId, String index,
                       String programTypeId, String source) {
        assertEnabled();

        String submitUrl = webUrl + "/problemset/submit";
        String page = get(cookieHeader, userAgent, submitUrl);
        if (isLoginPage(page)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "CF_SESSION_INVALID",
                "Your Codeforces session has expired. Reconnect your account and try again.");
        }

        String csrf = csrfToken(page);
        if (csrf == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "CF_SUBMIT_FAILED",
                "Could not read the CSRF token from the Codeforces submit page.");
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("csrf_token", csrf);
        form.put("ftaa", randomString(18, ALNUM));
        form.put("bfaa", randomString(32, HEX));
        form.put("action", "submitSolutionFormSubmitted");
        form.put("submittedProblemCode", contestId + index.toUpperCase());
        form.put("programTypeId", programTypeId);
        form.put("source", source);
        form.put("tabSize", "4");
        form.put("sourceFile", "");

        String result = postMultipart(cookieHeader, userAgent,
            submitUrl + "?csrf_token=" + URLEncoder.encode(csrf, StandardCharsets.UTF_8),
            form, submitUrl);

        String error = firstError(result);
        if (error != null) {
            throw ApiException.badRequest("Codeforces refused the submission: " + error);
        }

        Long id = firstSubmissionId(result);
        if (id == null) {
            // We ended up somewhere unexpected. The submission may well have landed, so say
            // that rather than implying it failed and inviting a duplicate.
            throw new ApiException(HttpStatus.BAD_GATEWAY, "CF_SUBMIT_UNCONFIRMED",
                "The submission was posted but Codeforces did not return a submission id. "
                    + "Check your submissions page before resubmitting.");
        }
        return id;
    }

    /**
     * Reads the language dropdown straight off the submit page. This is the only reliable
     * source of programTypeId values — Codeforces changes them as compilers are added and
     * retired.
     */
    public List<PracticeDto.LanguageOption> scrapeLanguages(String cookieHeader, String userAgent) {
        String page = get(cookieHeader, userAgent, webUrl + "/problemset/submit");
        if (isLoginPage(page)) return List.of();

        List<PracticeDto.LanguageOption> out = new ArrayList<>();
        for (Element opt : Jsoup.parse(page).select("select[name=programTypeId] option")) {
            String value = opt.attr("value").trim();
            String label = opt.text().trim();
            if (!value.isEmpty() && !label.isEmpty()) {
                out.add(new PracticeDto.LanguageOption(value, label));
            }
        }
        return out;
    }

    // ------------------------------------------------------- contest variants

    /**
     * Submits into a running contest.
     *
     * This is deliberately a separate path from {@link #submit}, because the contest form is
     * not the problemset form: it posts to /contest/{id}/submit and names the problem with a
     * {@code submittedProblemIndex} select rather than a {@code submittedProblemCode} input.
     * Posting the problemset shape during a contest lands the solution as a PRACTICE
     * submission, which scores nothing — so the two must not be collapsed into one method.
     */
    public long submitToContest(String cookieHeader, String userAgent, int contestId, String index,
                                String programTypeId, String source) {
        assertEnabled();

        String submitUrl = webUrl + "/contest/" + contestId + "/submit";
        String page = get(cookieHeader, userAgent, submitUrl);
        if (isLoginPage(page)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "CF_SESSION_INVALID",
                "Your Codeforces session has expired. Reconnect your account and try again.");
        }

        // No problem select means Codeforces is not offering this contest to this account:
        // not registered, or it has not started yet.
        List<String> indexes = problemIndexesOn(page);
        if (indexes.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CF_CONTEST_UNAVAILABLE",
                "Codeforces is not accepting submissions for contest " + contestId
                    + " on this account. Check you are registered and the contest has started.");
        }
        String wanted = index.toUpperCase();
        if (!indexes.contains(wanted)) {
            throw ApiException.badRequest("Contest " + contestId + " has no problem "
                + wanted + ". It offers: " + String.join(", ", indexes));
        }

        String csrf = csrfToken(page);
        if (csrf == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "CF_SUBMIT_FAILED",
                "Could not read the CSRF token from the Codeforces contest submit page.");
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("csrf_token", csrf);
        form.put("ftaa", randomString(18, ALNUM));
        form.put("bfaa", randomString(32, HEX));
        form.put("action", "submitSolutionFormSubmitted");
        form.put("submittedProblemIndex", wanted);
        form.put("programTypeId", programTypeId);
        form.put("source", source);
        form.put("tabSize", "4");
        form.put("sourceFile", "");

        String result = postMultipart(cookieHeader, userAgent,
            submitUrl + "?csrf_token=" + URLEncoder.encode(csrf, StandardCharsets.UTF_8),
            form, submitUrl);

        String error = firstError(result);
        if (error != null) {
            throw ApiException.badRequest("Codeforces refused the submission: " + error);
        }

        Long id = firstSubmissionId(result);
        if (id == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "CF_SUBMIT_UNCONFIRMED",
                "The submission was posted but Codeforces did not return a submission id. "
                    + "Check your contest submissions before resubmitting.");
        }
        return id;
    }

    /**
     * Language dropdown for one contest. Older contests offer far fewer compilers than the
     * problemset page does, so this must be read per contest rather than reused.
     */
    public List<PracticeDto.LanguageOption> scrapeContestLanguages(String cookieHeader, String userAgent,
                                                                   int contestId) {
        String page = get(cookieHeader, userAgent, webUrl + "/contest/" + contestId + "/submit");
        if (isLoginPage(page)) return List.of();

        List<PracticeDto.LanguageOption> out = new ArrayList<>();
        for (Element opt : Jsoup.parse(page).select("select[name=programTypeId] option")) {
            String value = opt.attr("value").trim();
            String label = opt.text().trim();
            if (!value.isEmpty() && !label.isEmpty()) {
                out.add(new PracticeDto.LanguageOption(value, label));
            }
        }
        return out;
    }

    /**
     * Whether Codeforces will currently accept submissions for this contest on this account.
     * Used as the registration check — CF exposes no API for "am I registered", but it only
     * renders the problem select to an account that may submit.
     */
    public boolean canSubmitTo(String cookieHeader, String userAgent, int contestId) {
        try {
            String page = get(cookieHeader, userAgent, webUrl + "/contest/" + contestId + "/submit");
            return !isLoginPage(page) && !problemIndexesOn(page).isEmpty();
        } catch (Exception e) {
            log.debug("Contest {} availability check failed: {}", contestId, e.getMessage());
            return false;
        }
    }

    /** Problem letters offered by a contest submit page, in the order CF lists them. */
    private List<String> problemIndexesOn(String html) {
        List<String> out = new ArrayList<>();
        for (Element opt : Jsoup.parse(html)
                .select("select[name=submittedProblemIndex] option")) {
            String value = opt.attr("value").trim();
            if (!value.isEmpty()) out.add(value.toUpperCase());
        }
        return out;
    }

    // --------------------------------------------------------- reading source

    /**
     * Reads the source of one submission back off Codeforces.
     *
     * This exists because Codeforces is, for submissions made outside CPIntel, the only place
     * the code lives — and the whole point of the feature is that the user never has to open
     * that page themselves. Anything submitted *through* CPIntel is archived as it is sent and
     * never reaches this method.
     *
     * The ordering here is the entire trick, and it is not the obvious one.
     *
     * Codeforces puts {@code /contest/{id}/submission/{id}} behind a JavaScript challenge —
     * an obfuscated fingerprint script behind "Your browser is being checked". No cookie gets
     * past it: a request carrying the browser's own complete jar, {@code pow} and all, still
     * gets the interstitial, for the session owner's own submission. Scraping that page is a
     * dead end, and reading the source off it is not possible at all.
     *
     * {@code POST /data/submitSource} — the call the page's own script makes — is *not* behind
     * the challenge. It needs a CSRF token, which is why the naive order fails: taking the
     * token off the submission page means being blocked before ever reaching the endpoint. The
     * token is per session, not per page, so it comes from any unchallenged page instead.
     *
     * The rendered page stays as a fallback for the day the challenge is lifted or the JSON
     * endpoint is renamed — cheap insurance, since by then this method is already failing.
     */
    public SubmissionDetail fetchSubmission(String cookieHeader, String userAgent, int contestId,
                                            long submissionId) {
        String csrf = csrfForSession(cookieHeader, userAgent);
        if (csrf != null) {
            SubmissionDetail detail = detailFromAjax(cookieHeader, userAgent, csrf, submissionId);
            if (detail != null) return detail;
        }

        // Fallback: whatever the page is willing to render. Source only — the per-test data
        // lives in the JSON response, never in the page markup.
        String url = webUrl + "/contest/" + contestId + "/submission/" + submissionId;
        String page = get(cookieHeader, userAgent, url);
        if (isLoginPage(page)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "CF_SESSION_INVALID",
                "Your Codeforces session has expired, so your old code cannot be read. "
                    + "Reconnect your account.");
        }
        if (!isBrowserCheck(page)) {
            String source = sourceFromPage(page);
            if (source != null) {
                return new SubmissionDetail(source, null, 0, List.of());
            }
        }

        throw new ApiException(HttpStatus.NOT_FOUND, "CF_SOURCE_UNAVAILABLE",
            "Codeforces did not return the source for submission " + submissionId
                + ". It may belong to another account, or be hidden while its contest runs.");
    }

    /**
     * A CSRF token for this session, taken from a page Codeforces still serves plainly.
     *
     * Cached briefly because the History panel invites clicking through several submissions in
     * a row, and the token is good for all of them — without this, every click would drag down
     * a 200KB homepage first.
     */
    private String csrfForSession(String cookieHeader, String userAgent) {
        String key = Integer.toHexString(cookieHeader.hashCode());
        CsrfEntry hit = csrfCache.get(key);
        if (hit != null && Instant.now().isBefore(hit.at().plus(CSRF_TTL))) return hit.token();

        String token = csrfToken(get(cookieHeader, userAgent, webUrl + "/"));
        if (token != null) csrfCache.put(key, new CsrfEntry(token, Instant.now()));
        return token;
    }

    /** The source as the page renders it, or null if the element is absent or empty. */
    private String sourceFromPage(String html) {
        Element pre = Jsoup.parse(html).selectFirst("#program-source-text");
        if (pre == null) return null;
        // wholeText, not text: text() collapses runs of whitespace, which would return the
        // program as a single line with every indent gone.
        String source = pre.wholeText();
        return source == null || source.isBlank() ? null : normaliseNewlines(source);
    }

    /**
     * The JSON route, and in practice the only one that works.
     *
     * Returns null rather than throwing so the caller can fall back and report once. The
     * Referer is not optional — Codeforces answers 403 without it.
     *
     * The response is far richer than the source: for every test Codeforces is willing to
     * show, it carries the input, the expected answer, what the program actually printed, the
     * checker's own message, and the time and memory it took. That is the difference between
     * "wrong answer on test 2" and being able to see why, which is the entire reason a user
     * would otherwise open the submission page.
     */
    private SubmissionDetail detailFromAjax(String cookieHeader, String userAgent,
                                            String csrf, long submissionId) {
        try {
            String body = "submissionId=" + submissionId
                + "&csrf_token=" + URLEncoder.encode(csrf, StandardCharsets.UTF_8);

            CfWebFetcher.Response res = fetcher.post(webUrl + "/data/submitSource",
                cookieHeader, uaOr(userAgent),
                "application/x-www-form-urlencoded; charset=UTF-8",
                body.getBytes(StandardCharsets.UTF_8),
                Map.of("X-Requested-With", "XMLHttpRequest",
                       "Referer", webUrl + "/", "Origin", webUrl));
            if (res.status() != 200) {
                log.debug("submitSource for {} answered {}", submissionId, res.status());
                return null;
            }

            return parseSubmissionDetail(res.body());
        } catch (Exception e) {
            log.debug("submitSource failed for {}: {}", submissionId, e.getMessage());
            return null;
        }
    }

    /**
     * Maps a {@code /data/submitSource} response onto {@link SubmissionDetail}.
     *
     * Package-private and free of I/O so the shape can be pinned by a test. This is the part
     * that rots: Codeforces owns the field names, and a silent change here would turn a real
     * failing test into an empty panel rather than into an error.
     *
     * Returns null when the response carries no source, which is how Codeforces answers for a
     * submission it will not show.
     */
    static SubmissionDetail parseSubmissionDetail(String json) throws Exception {
        JsonNode root = JSON.readTree(json);
        JsonNode sourceNode = root.get("source");
        if (sourceNode == null || sourceNode.isNull()) return null;
        String source = sourceNode.asText();
        if (source.isBlank()) return null;

        return new SubmissionDetail(
            normaliseNewlines(source),
            compilationErrorOf(root),
            intOf(root, "testCount", 0),
            testsOf(root));
    }

    /**
     * The per-test rows, in order.
     *
     * Codeforces flattens them into the top-level object as {@code input#1}, {@code answer#1}
     * and so on, so they are read by walking the index until one goes missing rather than by
     * trusting {@code testCount} — during a running contest the count is reported while the
     * data behind it is withheld, and an empty list is the honest answer there.
     */
    private static List<CfTest> testsOf(JsonNode root) {
        List<CfTest> tests = new ArrayList<>();
        for (int i = 1; i <= MAX_TESTS; i++) {
            JsonNode verdict = root.get("verdict#" + i);
            JsonNode input = root.get("input#" + i);
            if (verdict == null && input == null) break;

            String in = textOf(root, "input#" + i);
            String out = textOf(root, "output#" + i);
            String expected = textOf(root, "answer#" + i);

            tests.add(new CfTest(
                i,
                textOf(root, "verdict#" + i),
                in, out, expected,
                textOf(root, "checkerStdoutAndStderr#" + i),
                intOrNull(root, "exitCode#" + i),
                intOrNull(root, "timeConsumed#" + i),
                longOrNull(root, "memoryConsumed#" + i),
                isTruncated(in) || isTruncated(out) || isTruncated(expected)));
        }
        return tests;
    }

    /**
     * Codeforces clips test data at roughly half a kilobyte and marks it with a trailing
     * ellipsis. The length check is a backstop for the fields it clips without marking — a
     * caveat shown unnecessarily costs nothing, whereas presenting a clipped input as the
     * whole test invites someone to debug against data that was never complete.
     */
    private static boolean isTruncated(String value) {
        if (value == null) return false;
        return value.endsWith("...") || value.length() >= CF_TEST_DATA_CAP;
    }

    /** {@code compilationError} is the string "false" when there was none. */
    private static String compilationErrorOf(JsonNode root) {
        JsonNode node = root.get("compilationError");
        if (node == null || node.isNull()) return null;
        String value = node.asText();
        if (value.isBlank() || "false".equalsIgnoreCase(value)) return null;
        return normaliseNewlines(value);
    }

    private static String textOf(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) return null;
        String value = node.asText();
        return value.isEmpty() ? null : normaliseNewlines(value);
    }

    private static int intOf(JsonNode root, String field, int fallback) {
        Integer value = intOrNull(root, field);
        return value == null ? fallback : value;
    }

    private static Integer intOrNull(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) return null;
        try {
            return Integer.valueOf(node.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long longOrNull(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) return null;
        try {
            return Long.valueOf(node.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** CF serves CRLF; the editor and the local runner both want LF. */
    private static String normaliseNewlines(String s) {
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    // ------------------------------------------------------------- internals

    private void assertEnabled() {
        if (!submitEnabled) {
            throw ApiException.forbidden(
                "Auto-submit is disabled on this deployment (cpintel.practice.submit-enabled).");
        }
    }

    private String get(String cookieHeader, String userAgent, String url) {
        return fetcher.get(url, cookieHeader, uaOr(userAgent), Map.of()).body();
    }

    /** The submit form is enctype=multipart/form-data because of the sourceFile field. */
    private String postMultipart(String cookieHeader, String userAgent, String url,
                                 Map<String, String> form, String referer) {
        String boundary = "----CPIntel" + randomString(24, ALNUM);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            for (Map.Entry<String, String> e : form.entrySet()) {
                buf.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                if ("sourceFile".equals(e.getKey())) {
                    buf.write(("Content-Disposition: form-data; name=\"sourceFile\"; "
                        + "filename=\"\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                } else {
                    buf.write(("Content-Disposition: form-data; name=\"" + e.getKey()
                        + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    buf.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                }
                buf.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            buf.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            // The envelope is still built here, byte for byte as before; only the process
            // that opens the socket has changed.
            return fetcher.post(url, cookieHeader, uaOr(userAgent),
                "multipart/form-data; boundary=" + boundary,
                buf.toByteArray(),
                Map.of("Referer", referer, "Origin", webUrl)).body();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "CF_UNREACHABLE",
                "Submission POST failed: " + e.getMessage());
        }
    }

    private String csrfToken(String html) {
        Document doc = Jsoup.parse(html);
        Element input = doc.selectFirst("input[name=csrf_token]");
        if (input != null && !input.attr("value").isBlank()) return input.attr("value");
        Element meta = doc.selectFirst("meta[name=X-Csrf-Token]");
        if (meta != null && !meta.attr("content").isBlank()) return meta.attr("content");
        return null;
    }

    private boolean isLoginPage(String html) {
        return html.contains("name=\"handleOrEmail\"") || html.contains("id=\"handleOrEmail\"");
    }

    /**
     * Codeforces' own anti-scraping interstitial — obfuscated JS that derives the RCPC cookie
     * and reloads. Not a login wall and not Cloudflare: it is served to clients that have not
     * passed the check, on the heavier pages only. Statement pages come back without it, which
     * is why the statement scraper needs no session and this does.
     */
    private boolean isBrowserCheck(String html) {
        return html.contains("Your browser is being checked");
    }

    /**
     * The handle Codeforces considers signed in, or null if the session is not logged in.
     *
     * Only the header counts. A logged-OUT Codeforces homepage still carries ~350 /profile/
     * links — the "top rated" sidebar, recent-actions authors — so a page-wide search reports
     * whoever currently tops the rating list. There used to be exactly that fallback here,
     * which meant a dead cookie was accepted and stored as if the user were Benq. Failing
     * closed is the only safe behaviour: better to say "not logged in" than to attribute the
     * session to the wrong account.
     */
    private String loggedInHandle(String html) {
        Document doc = Jsoup.parse(html);

        // A sign-in link means Codeforces is rendering the logged-out chrome.
        if (doc.selectFirst("div#header a[href^=/enter]") != null) return null;

        Element link = doc.selectFirst("div#header a[href^=/profile/]");
        if (link == null) link = doc.selectFirst("div.lang-chooser a[href^=/profile/]");
        if (link == null) return null;          // no page-wide fallback, deliberately

        String href = link.attr("href");
        String handle = href.substring(href.lastIndexOf('/') + 1);
        return handle.isBlank() ? null : handle;
    }

    /** CF ships empty span.error placeholders on the form, so take the first with text. */
    private String firstError(String html) {
        for (Element err : Jsoup.parse(html).select("span.error")) {
            if (!err.text().isBlank()) return err.text().trim();
        }
        if (html.contains("You have submitted exactly the same code before")) {
            return "You have submitted exactly the same code before.";
        }
        return null;
    }

    /** After a successful submit CF lands on the status page, newest submission first. */
    private Long firstSubmissionId(String html) {
        Element row = Jsoup.parse(html)
            .selectFirst("table.status-frame-datatable tr[data-submission-id]");
        if (row != null) {
            try {
                return Long.parseLong(row.attr("data-submission-id"));
            } catch (NumberFormatException ignored) {
                // fall through to the unconfirmed path
            }
        }
        return null;
    }

    private String randomString(int length, String alphabet) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
