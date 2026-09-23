package com.cpintel.integration.domjudge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A signed-in session against DOMjudge's <em>web</em> interface, as one particular account.
 *
 * <h2>Why this is needed at all</h2>
 *
 * <p>DOMjudge has two front doors and they do not accept the same credential. The API firewall
 * speaks HTTP Basic, which is what {@link DomjudgeClient} uses everywhere else. The web
 * interface does not: it is a Symfony form login, and a request carrying a perfectly valid
 * Basic header is redirected to {@code /login} exactly as an anonymous one is. Confirmed
 * against a live 8.0.0 instance — a deliberately wrong Basic credential produced {@code 401}
 * from {@code /api/v4/contests} and {@code 302 → /login} from {@code /team/problems/1/text}.
 *
 * <p>That matters because <b>DOMjudge 8.0's API cannot serve a problem statement at all</b>.
 * Its OpenAPI document lists no statement route, and {@code /contests/{cid}/problems/{id}}
 * carries no href to one; the route CPIntel was calling arrived in a later release. The only
 * thing that serves a statement on 8.0 is {@code /team/problems/{id}/text} on the web side —
 * so reaching it means holding a session, which is what this class is for.
 *
 * <h2>What it does</h2>
 *
 * <p>Fetches {@code /login} for a CSRF token and a session cookie, posts the credential back,
 * and keeps the resulting cookie. Sessions are cached per account and reused; a request that
 * comes back redirected to {@code /login} means the session lapsed, and the caller discards it
 * and asks for a fresh one.
 *
 * <p><b>It holds a cookie, not a password.</b> The password comes from
 * {@link DomjudgeCredentialStore} for the length of one login and is not retained here. The
 * cookie is in memory only — never Postgres, never Mongo — and expires on its own, so a process
 * restart or an idle hour leaves nothing behind.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DomjudgeWebSession {

    /**
     * How long a cached cookie is reused before a fresh login.
     *
     * Comfortably inside any sane PHP session lifetime. A session that lapses earlier is not a
     * problem — the caller retries once with a new one — so this trades a rare extra login
     * against holding a cookie longer than it is good for.
     */
    private static final Duration TTL = Duration.ofMinutes(20);

    /** Symfony renders this into the form; the POST is rejected without it. */
    private static final Pattern CSRF =
        Pattern.compile("name=\"_csrf_token\"[^>]*value=\"([^\"]+)\"");

    private static final Pattern SESSION_COOKIE =
        Pattern.compile("(PHPSESSID|MOCKSESSID)=([^;]+)", Pattern.CASE_INSENSITIVE);

    private final DomjudgeClient domjudge;

    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();

    private record Entry(String cookie, Instant at) {
        boolean fresh() {
            return at.plus(TTL).isAfter(Instant.now());
        }
    }

    /**
     * The cookie to send as this account, logging in if there is not a usable one already.
     *
     * @return a {@code Cookie} header value, or null when the sign-in did not work — the
     *         caller then falls back to whatever anonymous route it has, rather than failing
     */
    public String cookieFor(DomjudgeCredentialStore.Stored as) {
        if (as == null || as.username() == null || as.username().isBlank()) return null;

        Entry cached = sessions.get(as.username());
        if (cached != null && cached.fresh()) return cached.cookie();

        String cookie = login(as);
        if (cookie != null) sessions.put(as.username(), new Entry(cookie, Instant.now()));
        return cookie;
    }

    /**
     * A session cookie pinned to one contest.
     *
     * <p>DOMjudge's team pages serve only problems of the session's "current contest", which
     * the {@code domjudge_cid} cookie selects and which otherwise defaults to the first active
     * one. On an instance that still lists an earlier contest — a finished practice round with
     * no deactivation time — every problem of the running contest answered 404 as if it had
     * no statement.
     */
    public static String withContest(String cookie, String contestId) {
        if (cookie == null || contestId == null || contestId.isBlank()) return cookie;
        return cookie + "; domjudge_cid=" + contestId;
    }

    /** Forgets this account's session, so the next call signs in again. */
    public void invalidate(DomjudgeCredentialStore.Stored as) {
        if (as != null && as.username() != null) sessions.remove(as.username());
    }

    // ---------------------------------------------------------------- login

    private String login(DomjudgeCredentialStore.Stored as) {
        try {
            WebClient web = domjudge.plainWebClient();

            // Step one: the form, for a CSRF token and the session cookie it is bound to.
            var page = web.get().uri("/login")
                .accept(MediaType.TEXT_HTML)
                .retrieve()
                .toEntity(String.class)
                .block(Duration.ofSeconds(20));
            if (page == null || page.getBody() == null) return null;

            String sessionCookie = cookieFrom(page.getHeaders());
            Matcher csrf = CSRF.matcher(page.getBody());
            if (!csrf.find()) {
                log.warn("DOMjudge login form carried no CSRF token; the web interface cannot "
                    + "be signed into, so problem statements will fall back to public routes.");
                return null;
            }

            // Step two: post the credential back on that same session.
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("_csrf_token", csrf.group(1));
            form.add("_username", as.username());
            form.add("_password", as.password());

            var response = web.post().uri("/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header(HttpHeaders.COOKIE, sessionCookie)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                // A successful login answers 302 to the team page; a failed one answers 302
                // back to /login. Both are redirects, so the status cannot distinguish them —
                // the caller finds out when it uses the cookie, which is the honest place.
                .onStatus(status -> status.is3xxRedirection(), r -> reactor.core.publisher.Mono.empty())
                .toBodilessEntity()
                .block(Duration.ofSeconds(20));

            if (response == null) return null;
            String after = cookieFrom(response.getHeaders());
            String cookie = after != null ? after : sessionCookie;

            log.info("Signed in to the DOMjudge web interface as {}", as.username());
            return cookie;

        } catch (Exception e) {
            // Never the password, and never the cause's message verbatim — a Symfony error page
            // can echo submitted form fields back.
            log.warn("Could not sign in to the DOMjudge web interface as {}: {}",
                as.username(), e.getClass().getSimpleName());
            return null;
        }
    }

    private String cookieFrom(HttpHeaders headers) {
        for (String value : headers.getOrEmpty(HttpHeaders.SET_COOKIE)) {
            Matcher m = SESSION_COOKIE.matcher(value);
            if (m.find()) return m.group(1) + "=" + m.group(2);
        }
        return null;
    }
}
