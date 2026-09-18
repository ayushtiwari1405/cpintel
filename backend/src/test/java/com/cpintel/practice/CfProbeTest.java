package com.cpintel.practice;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live probe against codeforces.com.
 *
 * These deliberately do NOT boot a Spring context, so they run without Postgres, Redis or
 * Mongo — just the JVM and a network connection. That makes them the fastest way to find out
 * whether the scraper selectors and the submit form fields still match what Codeforces is
 * actually serving, which is the part of this feature most likely to rot.
 *
 * Run:
 *   ./mvnw test -Dtest=CfProbeTest
 *
 * Everything past the statement scrape needs a real session and skips without one:
 *   export CF_COOKIE='JSESSIONID=...; X-User-Sha1=...'
 *   ./mvnw test -Dtest=CfProbeTest
 *
 * The submit test makes a REAL submission to your account and stays off unless you ask:
 *   export CF_ALLOW_SUBMIT=true
 */
class CfProbeTest {

    private static final String WEB_URL = "https://codeforces.com";

    private static final int PROBE_CONTEST = Integer.parseInt(
        System.getenv().getOrDefault("CF_CONTEST", "4"));
    private static final String PROBE_INDEX =
        System.getenv().getOrDefault("CF_INDEX", "A");

    private static String cookie() {
        return System.getenv("CF_COOKIE");
    }

    /**
     * The User-Agent to replay the session under.
     *
     * <p>Cloudflare binds the cf_clearance cookie in CF_COOKIE to the User-Agent of the browser
     * that earned it, so a probe run under any other string gets the interstitial and every
     * assertion here fails for a reason that has nothing to do with the selectors it is meant
     * to be checking. Set CF_USER_AGENT to the browser's own when copying a fresh cookie.
     */
    private static String userAgent() {
        return System.getenv().getOrDefault("CF_USER_AGENT",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/152.0.0.0 Safari/537.36");
    }

    /**
     * A fetcher configured the way Spring would configure it.
     *
     * <p>Codeforces' HTML is fetched by curl rather than from the JVM, because Cloudflare
     * refuses the JVM's TLS fingerprint outright — see {@link CfWebFetcher}. That makes curl a
     * real prerequisite of this probe, alongside the network it already needed.
     */
    private CfWebFetcher fetcher() {
        CfWebFetcher f = new CfWebFetcher();
        ReflectionTestUtils.setField(f, "curlPath", "curl");
        ReflectionTestUtils.setField(f, "timeoutSeconds", 30);
        return f;
    }

    private CfStatementScraper scraper() {
        CfStatementScraper s = new CfStatementScraper(fetcher());
        ReflectionTestUtils.setField(s, "webUrl", WEB_URL);
        return s;
    }

    private CfWebSubmitClient client() {
        CfWebSubmitClient c = new CfWebSubmitClient(fetcher());
        ReflectionTestUtils.setField(c, "webUrl", WEB_URL);
        ReflectionTestUtils.setField(c, "submitEnabled", true);
        return c;
    }

    // ------------------------------------------------------------ needs a session

    /**
     * This used to need no authentication, and the change is not incidental.
     *
     * <p>Problemset statements are public, and this probe read one anonymously for years. They
     * are now behind a Cloudflare challenge that answers any client without a {@code
     * cf_clearance} cookie with 403 and an interstitial — measured: the same fetch returns 200
     * with a session's cookies and 403 without them. A statement simply cannot be scraped
     * anonymously any more, so asserting that it can is asserting something unachievable, and
     * the test would sit permanently red while telling nobody anything about the selectors it
     * exists to check.
     *
     * <p>It therefore takes the same session as every other probe here, and skips without one.
     */
    @Test
    @DisplayName("Statement scrape: selectors still match Codeforces markup")
    void scrapesStatement() {
        assumeCookie();
        PracticeDto.ProblemDetail d = scraper().fetch(
            PROBE_CONTEST, PROBE_INDEX, 800, List.of("math"),
            CfSessionStore.sanitiseCookieHeader(cookie()), userAgent());

        System.out.printf("%n=== %s%s ===%n", d.contestId(), d.index());
        System.out.println("name        : " + d.name());
        System.out.println("time limit  : " + d.timeLimit());
        System.out.println("memory limit: " + d.memoryLimit());
        System.out.println("legend len  : " + len(d.legendHtml()));
        System.out.println("input spec  : " + len(d.inputSpecHtml()));
        System.out.println("output spec : " + len(d.outputSpecHtml()));
        System.out.println("samples     : " + d.samples().size());
        d.samples().forEach(s -> System.out.printf(
            "  in=%s | out=%s%n", oneLine(s.input()), oneLine(s.output())));

        assertTrue(d.statementAvailable(),
            "Could not reach or parse the problem page — check network, then the selectors.");
        assertNotNull(d.legendHtml(), "Legend selector broke: no unclassed child div found.");
        assertFalse(d.samples().isEmpty(),
            "Sample parsing broke: div.sample-tests markup has probably changed.");
        assertNotNull(d.timeLimit(), "Header parsing broke: div.time-limit not found.");
    }

    // -------------------------------------------------------- session required

    @Test
    @DisplayName("Session: pasted cookies resolve to a logged-in handle")
    void resolvesHandle() {
        assumeCookie();
        String handle = client().resolveHandle(CfSessionStore.sanitiseCookieHeader(cookie()), userAgent());
        System.out.println("\nlogged in as: " + handle);
        assertNotNull(handle,
            "Cookies are not logged in. Re-copy the Cookie header from a signed-in tab.");
    }

    @Test
    @DisplayName("Languages: programTypeId dropdown is readable from the submit page")
    void scrapesLanguages() {
        assumeCookie();
        List<PracticeDto.LanguageOption> langs =
            client().scrapeLanguages(CfSessionStore.sanitiseCookieHeader(cookie()), userAgent());

        System.out.println("\n=== programTypeId options ===");
        langs.forEach(l -> System.out.printf("  %-5s %s%n", l.id(), l.label()));

        assertFalse(langs.isEmpty(),
            "No languages found — session is dead, or the select name changed.");
        // Sanity-check the fallback list in application.yml against reality.
        System.out.println("\nCross-check these ids against cpintel.practice.default-languages.");
    }

    @Test
    @DisplayName("Cookie sanitiser keeps Codeforces' anti-bot cookies, drops analytics")
    void sanitisesCookies() {
        String raw = "JSESSIONID=abc123; _ga=GA1.2.999; X-User-Sha1=deadbeef; "
            + "39ce7=fingerprint; pow=proofofwork; 70a7c28f3de=randomname; "
            + "_gid=GA1.2.1; _gat_gtag_UA_743380_5=1";
        String clean = CfSessionStore.sanitiseCookieHeader(raw);

        System.out.println("\nsanitised: " + clean);
        assertTrue(clean.contains("JSESSIONID=abc123"));
        assertTrue(clean.contains("X-User-Sha1=deadbeef"));
        assertTrue(clean.contains("39ce7=fingerprint"));

        // The regression this pins. Codeforces answers its JS challenge with a `pow` cookie
        // and a token under a name that changes — an allowlist dropped both, and every
        // submission page then came back as the interstitial instead of the source.
        assertTrue(clean.contains("pow=proofofwork"),
            "Dropping the proof-of-work cookie breaks reading submission source.");
        assertTrue(clean.contains("70a7c28f3de=randomname"),
            "A randomly-named Codeforces cookie must survive — it cannot be allowlisted.");

        assertFalse(clean.contains("_ga="), "Analytics cookie should have been dropped.");
        assertFalse(clean.contains("_gid"), "Analytics cookie should have been dropped.");
        assertFalse(clean.contains("_gat"), "Analytics cookie should have been dropped.");
    }

    @Test
    @DisplayName("Cookie sanitiser refuses a header with no session cookie")
    void rejectsHeaderWithoutSession() {
        assertThrows(RuntimeException.class,
            () -> CfSessionStore.sanitiseCookieHeader("_ga=GA1.2.999; pow=proofofwork"),
            "A header with no JSESSIONID is not a session and must be refused.");
    }

    /**
     * Unlike {@link #scrapesStatement}, this one cannot run without a session, and the reason
     * is not authorisation.
     *
     * Codeforces serves problem statements to anyone, but puts submission pages behind its
     * RCPC browser check — an obfuscated JS interstitial ("Your browser is being checked")
     * that no scraper passes. A logged-in session's cookies carry the answer to that check,
     * so the fetch works in the app and cannot be exercised anonymously here. Do not read a
     * skip as "the selectors are fine".
     */
    @Test
    @DisplayName("Source fetch: reads a submission's code back off Codeforces")
    void fetchesSubmissionSource() {
        assumeCookie();

        // Any submission of the probing account's own. Defaults to nothing, because there is
        // no id that is valid for everyone — CF only serves source for your own submissions
        // in most states.
        String raw = System.getenv("CF_SUBMISSION_ID");
        Assumptions.assumeTrue(raw != null && !raw.isBlank(),
            "Skipped — set CF_SUBMISSION_ID (and CF_CONTEST) to one of your own submissions.");

        CfWebSubmitClient.SubmissionDetail detail = client().fetchSubmission(
            CfSessionStore.sanitiseCookieHeader(cookie()), userAgent(),
            PROBE_CONTEST, Long.parseLong(raw));
        String source = detail.source();

        System.out.println("\nsource      : " + len(source));
        System.out.println("first line  : " + oneLine(source.lines().findFirst().orElse("")));
        System.out.println("line count  : " + source.lines().count());
        System.out.println("testCount   : " + detail.testCount());
        System.out.println("tests read  : " + detail.tests().size());
        detail.tests().forEach(t -> System.out.printf(
            "  test %-3d %-22s in=%s out=%s ans=%s%s%n",
            t.index(), t.verdict(), oneLine(t.input()), oneLine(t.output()),
            oneLine(t.answer()), t.truncated() ? "  (clipped)" : ""));
        if (detail.compilationError() != null) {
            System.out.println("compile err : " + oneLine(detail.compilationError()));
        }

        assertNotNull(source);
        assertFalse(source.isBlank(),
            "Empty source — both #program-source-text and /data/submitSource came back "
                + "with nothing. One of the two has changed.");
        // The failure this guards against is subtle: Jsoup's text() would return the whole
        // program collapsed onto one line, which still looks like a successful fetch.
        assertTrue(source.lines().count() > 1,
            "Source came back as a single line — whitespace is being collapsed somewhere, "
                + "so wholeText() has probably been swapped for text().");
        assertFalse(source.contains("\r"),
            "CRLF survived normaliseNewlines().");
    }

    // ------------------------------------------------- makes a real submission

    @Test
    @DisplayName("Submit: posts a real solution (opt in with CF_ALLOW_SUBMIT=true)")
    void submitsForReal() {
        assumeCookie();
        Assumptions.assumeTrue("true".equals(System.getenv("CF_ALLOW_SUBMIT")),
            "Skipped — set CF_ALLOW_SUBMIT=true to make an actual submission.");

        // 4A "Watermelon": read w, print YES if it splits into two even parts.
        String source = """
            #include <bits/stdc++.h>
            int main() { int w; std::cin >> w; std::cout << (w > 2 && w % 2 == 0 ? "YES" : "NO"); }
            """;
        String languageId = System.getenv().getOrDefault("CF_LANGUAGE_ID", "54");

        long id = client().submit(CfSessionStore.sanitiseCookieHeader(cookie()), userAgent(),
            4, "A", languageId, source);

        System.out.println("\nsubmission id: " + id);
        System.out.println("check: https://codeforces.com/submissions/<your-handle>");
        assertTrue(id > 0);
    }

    // ------------------------------------------------------------------ helpers

    private void assumeCookie() {
        Assumptions.assumeTrue(cookie() != null && !cookie().isBlank(),
            "Skipped — set CF_COOKIE to a Cookie header from a logged-in codeforces.com tab.");
    }

    private static String len(String s) {
        return s == null ? "null" : s.length() + " chars";
    }

    private static String oneLine(String s) {
        String t = s == null ? "-" : s.replace("\n", "\\n");
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }
}
