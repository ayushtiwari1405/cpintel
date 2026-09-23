package com.cpintel.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.cpintel.config.AppMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Puts a ceiling on the endpoints where one caller can spend a lot of somebody else's resources.
 *
 * <p>Three of them, chosen because each costs the server far more than it costs the caller:
 * signing in runs Argon2id, which is expensive on purpose; running code spawns a compiler; and
 * a sync fans out to a third-party API under a shared rate limit that everyone else needs too.
 *
 * <p>nginx already limits by address at the edge, but that only covers the deployment that runs
 * behind nginx, only stops floods rather than patient guessing, and knows nothing about accounts.
 * This sits behind it and applies per-account limits the edge cannot express. The per-email half
 * of the login limit is not here — it lives in
 * {@link com.cpintel.service.AuthService}, where the address has already been parsed out of the
 * body, rather than requiring this filter to buffer and re-parse every request.
 *
 * <p>Ordered after {@link JwtAuthFilter} so the authenticated limits can key on the user id
 * rather than the address, which is what makes them meaningful behind shared egress.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    public static final String LOGIN_IP = "login-ip";
    public static final String RECOVERY = "recovery";
    public static final String RUN      = "run";
    public static final String SYNC     = "sync";
    /** Guesses at an examination password, counted against the candidate. */
    public static final String EXAM_UNLOCK = "exam-unlock";

    private final RateLimitService rateLimiter;
    private final RateLimitProperties props;
    private final AppMetrics metrics;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String path = route(request);
        String method = request.getMethod();

        String bucket = null;
        String key = null;
        RateLimitProperties.Rule rule = null;

        if ("POST".equals(method)) {
            if (path.equals("/auth/login")) {
                bucket = LOGIN_IP;
                key = clientAddress(request);
                rule = props.getLogin();

            } else if (path.equals("/auth/forgot-password")
                    || path.equals("/auth/reset-password")
                    || path.equals("/auth/change-password")
                    || path.equals("/auth/register")) {
                bucket = RECOVERY;
                key = clientAddress(request);
                rule = props.getRecovery();

            } else if (path.startsWith("/exams/") && path.endsWith("/unlock")) {
                // Keyed on the candidate rather than the address, because a room full of people
                // sitting the same paper shares one address and one of them typing their code
                // wrongly must not lock out the other two hundred.
                bucket = EXAM_UNLOCK;
                key = principal();
                rule = props.getExamUnlock();

            } else if (path.equals("/run")) {
                bucket = RUN;
                key = principal();
                rule = props.getRun();

            } else if (path.startsWith("/integrations/") && path.endsWith("/sync")) {
                bucket = SYNC;
                key = principal();
                rule = props.getSync();
            }
        }

        if (rule != null && !rateLimiter.tryAcquire(bucket, key, rule)) {
            reject(response, bucket, key);
            return;
        }

        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String bucket, String key)
            throws IOException {
        long retryAfter = rateLimiter.retryAfterSeconds(bucket, key);
        metrics.throttled(bucket);

        log.warn("Rate limit exceeded: bucket={} key={} retryAfter={}s", bucket, key, retryAfter);

        response.setStatus(429);
        response.setContentType("application/json");
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.getWriter().write(
            "{\"code\":\"TOO_MANY_REQUESTS\",\"message\":\"Too many attempts. Try again in "
            + retryAfter + " seconds.\"}");
    }

    /**
     * The request path with the API prefix taken off, so the rules below name routes.
     *
     * <p>They used to be written as {@code path.endsWith("/api/auth/login")} against a URI of
     * {@code /api/v1/auth/login}, which matches nothing — so every limit in this filter was
     * silently inert from the day the API was versioned. Matching the route rather than a
     * guess at the whole URI is what stops that recurring: a second version prefix changes
     * nothing here, and the comparisons are exact rather than suffix tests, so a new endpoint
     * cannot fall into somebody else's bucket by ending in the same word.
     */
    private String route(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        if (!path.startsWith("/api/")) return path;
        path = path.substring(4);                       // drop "/api"
        // Drop a version segment if there is one: /v1, /v2...
        int next = path.indexOf('/', 1);
        String head = next < 0 ? path.substring(1) : path.substring(1, next);
        if (head.length() > 1 && head.charAt(0) == 'v'
                && head.chars().skip(1).allMatch(Character::isDigit)) {
            path = next < 0 ? "/" : path.substring(next);
        }
        // A trailing slash is the same route.
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }

    /**
     * The caller's address.
     *
     * Behind nginx this is the real client rather than the proxy, because the prod profile sets
     * {@code server.forward-headers-strategy} and the backend port is bound to loopback so the
     * forwarded header cannot be set by anyone but the proxy.
     */
    private String clientAddress(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    /** The authenticated user id, or the address when there is no authentication yet. */
    private String principal() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth.getPrincipal() == null
            ? null
            : String.valueOf(auth.getPrincipal());
    }
}
