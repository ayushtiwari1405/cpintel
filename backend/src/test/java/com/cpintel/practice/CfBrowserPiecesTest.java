package com.cpintel.practice;

import com.cpintel.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * The pieces of a Codeforces submission the user's browser carries: reading the pages it
 * fetched, and the form it should post. Same page knowledge as the server path — these pin that
 * the browser path reads pages exactly as a server fetch would.
 */
class CfBrowserPiecesTest {

    private CfWebSubmitClient client;
    private CfStatementScraper scraper;

    private static final String SIGNED_IN = """
        <html><body><div id="header"><a href="/profile/ayu741">ayu741</a>
        <a href="/logout">Logout</a></div></body></html>""";

    private static final String SIGNED_OUT = """
        <html><body><div id="header"><a href="/enter?back=%2F">Enter</a></div></body></html>""";

    private static final String SUBMIT_PAGE = """
        <html><head><meta name="X-Csrf-Token" content="tok123"/></head><body>
        <form><input type="hidden" name="csrf_token" value="tok123"/>
        <select name="programTypeId">
          <option value="89">GNU G++20 13.2 (64 bit, winlibs)</option>
          <option value="70">PyPy 3.10 (7.3.15, 64bit)</option>
        </select></form></body></html>""";

    private static final String CONTEST_SUBMIT_PAGE = """
        <html><body><input type="hidden" name="csrf_token" value="ctok"/>
        <select name="submittedProblemIndex"><option value="A">A</option>
        <option value="B">B</option></select>
        <select name="programTypeId"><option value="89">GNU G++20</option></select>
        </body></html>""";

    private static final String LOGIN_PAGE = """
        <html><body><form><input id="handleOrEmail" name="handleOrEmail"/></form></body></html>""";

    @BeforeEach
    void setUp() {
        client = new CfWebSubmitClient(mock(CfWebFetcher.class));
        ReflectionTestUtils.setField(client, "webUrl", "https://codeforces.com");
        ReflectionTestUtils.setField(client, "submitEnabled", true);
        scraper = new CfStatementScraper(mock(CfWebFetcher.class));
        ReflectionTestUtils.setField(scraper, "webUrl", "https://codeforces.com");
    }

    @Test
    @DisplayName("reads the signed-in handle, and null when signed out")
    void handle() {
        assertEquals("ayu741", client.handleOn(SIGNED_IN));
        assertNull(client.handleOn(SIGNED_OUT));
    }

    @Test
    @DisplayName("reads the compiler list off a submit page, and nothing off the sign-in form")
    void languages() {
        List<PracticeDto.LanguageOption> options = client.languagesOn(SUBMIT_PAGE);
        assertEquals(2, options.size());
        assertEquals("89", options.get(0).id());
        assertTrue(client.languagesOn(LOGIN_PAGE).isEmpty());
    }

    @Test
    @DisplayName("builds the problemset form with the page's csrf token")
    void problemsetForm() {
        var form = client.submitForm(SUBMIT_PAGE, 2266, "a", false, "89", "int main(){}");
        assertEquals("https://codeforces.com/problemset/submit?csrf_token=tok123", form.url());
        assertEquals("2266A", form.fields().get("submittedProblemCode"));
        assertEquals("tok123", form.fields().get("csrf_token"));
        assertEquals("int main(){}", form.fields().get("source"));
        assertFalse(form.fields().containsKey("submittedProblemIndex"));
    }

    @Test
    @DisplayName("a contest form names the problem by index, and refuses one the contest lacks")
    void contestForm() {
        var form = client.submitForm(CONTEST_SUBMIT_PAGE, 2267, "b", true, "89", "x");
        assertEquals("https://codeforces.com/contest/2267/submit?csrf_token=ctok", form.url());
        assertEquals("B", form.fields().get("submittedProblemIndex"));
        assertFalse(form.fields().containsKey("submittedProblemCode"));

        assertThrows(ApiException.class,
            () -> client.submitForm(CONTEST_SUBMIT_PAGE, 2267, "Z", true, "89", "x"));
    }

    @Test
    @DisplayName("a signed-out browser is told to sign in rather than posting a form")
    void signedOutRefused() {
        ApiException e = assertThrows(ApiException.class,
            () -> client.submitForm(LOGIN_PAGE, 2266, "A", false, "89", "x"));
        assertEquals("CF_SESSION_INVALID", e.getCode());
    }

    @Test
    @DisplayName("reads the submission id off the answer, and Codeforces' own refusal when there is one")
    void submittedId() {
        String status = """
            <table class="status-frame-datatable"><tr data-submission-id="389034926">
            <td>x</td></tr></table>""";
        assertEquals(389034926L, client.submittedId(status, false));

        ApiException refused = assertThrows(ApiException.class, () -> client.submittedId(
            "<span class=\"error for__source\">You have submitted exactly the same code before</span>",
            false));
        assertTrue(refused.getMessage().contains("exactly the same code"));

        ApiException unknown = assertThrows(ApiException.class,
            () -> client.submittedId("<html></html>", true));
        assertEquals("CF_SUBMIT_UNCONFIRMED", unknown.getCode());
    }

    @Test
    @DisplayName("a challenge page is reported as the browser check, not as a missing problem")
    void statementChallenge() {
        var detail = scraper.parsePage("<title>Just a moment...</title><div id=cf_chl_opt></div>",
            2266, "A", 800, List.of("greedy"), false);
        assertFalse(detail.statementAvailable());
        assertEquals("BROWSER_CHECK", detail.statementIssue());
    }

    @Test
    @DisplayName("a real statement page is parsed exactly as a server fetch would be")
    void statementParsed() {
        String page = """
            <div class="problem-statement"><div class="header"><div class="title">A. Reverse</div>
            <div class="time-limit"><div class="property-title">time limit per test</div>1 second</div>
            <div class="memory-limit"><div class="property-title">memory limit per test</div>256 megabytes</div>
            </div><div><p>Reverse the digits.</p></div>
            <div class="input-specification"><div class="section-title">Input</div><p>N</p></div>
            <div class="output-specification"><div class="section-title">Output</div><p>R</p></div>
            <div class="sample-tests"><div class="sample-test"><div class="input"><pre>12345</pre></div>
            <div class="output"><pre>54321</pre></div></div></div></div>""";
        var detail = scraper.parsePage(page, 2266, "A", 800, List.of("greedy"), false);
        assertTrue(detail.statementAvailable());
        assertEquals("Reverse", detail.name());
        assertEquals(1, detail.samples().size());
        assertEquals("54321", detail.samples().get(0).output().trim());
    }
}
