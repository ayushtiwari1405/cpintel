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

    private final RateLimitService rateLimiter;
    private final RateLimitProperties props;
    private final AppMetrics metrics;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String path = request.getRequestURI();
        String method = request.getMethod();

        String bucket = null;
        String key = null;
        RateLimitProperties.Rule rule = null;

        if ("POST".equals(method)) {
            if (path.endsWith("/api/auth/login")) {
                bucket = LOGIN_IP;
                key = clientAddress(request);
                rule = props.getLogin();

            } else if (path.endsWith("/api/auth/forgot-password")
                    || path.endsWith("/api/auth/reset-password")
                    || path.endsWith("/api/auth/register")) {
                bucket = RECOVERY;
                key = clientAddress(request);
                rule = props.getRecovery();

            } else if (path.endsWith("/api/run")) {
                bucket = RUN;
                key = principal();
                rule = props.getRun();

            } else if (path.contains("/api/integrations/") && path.endsWith("/sync")) {
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
