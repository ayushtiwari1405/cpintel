package com.cpintel.practice;

import com.cpintel.exception.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The session's cookie header and user agent are written into curl's config, one directive a
 * line. A line break inside either would end its directive and make the rest a curl option of
 * the caller's choosing, so neither may carry one.
 */
class CfSessionInputTest {

    private CfWebFetcher fetcher() {
        CfWebFetcher f = new CfWebFetcher();
        // Never reached: the refusal comes before curl is started.
        ReflectionTestUtils.setField(f, "curlPath", "/nonexistent/curl");
        ReflectionTestUtils.setField(f, "timeoutSeconds", 5);
        return f;
    }

    @Test
    @DisplayName("a user agent with a line break is refused before curl runs")
    void userAgentLineBreak() {
        ApiException e = assertThrows(ApiException.class, () -> fetcher().get(
            "https://codeforces.com/", "JSESSIONID=abc", "Mozilla/5.0\noutput = /tmp/x", Map.of()));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
    }

    @Test
    @DisplayName("a cookie value with a line break is refused by the sanitiser")
    void cookieLineBreak() {
        assertThrows(ApiException.class,
            () -> CfSessionStore.sanitiseCookieHeader("JSESSIONID=abc; x=1\noutput = /tmp/x"));
        assertThrows(ApiException.class,
            () -> CfSessionStore.sanitiseCookieHeader("JSESSIONID=abc\r; x=1"));
    }

    @Test
    @DisplayName("a pasted header that only ends in a newline still works")
    void trailingNewline() {
        assertEquals("JSESSIONID=abc", CfSessionStore.sanitiseCookieHeader("JSESSIONID=abc\n"));
    }

    @Test
    @DisplayName("an ordinary user agent is not control-character text")
    void ordinaryUserAgent() {
        assertFalse(CfWebFetcher.hasControl("Mozilla/5.0 (X11; Linux x86_64) Chrome/130.0"));
        assertTrue(CfWebFetcher.hasControl("a\u007fb"));
    }
}
