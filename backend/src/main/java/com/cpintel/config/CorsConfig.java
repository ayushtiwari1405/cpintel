package com.cpintel.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which other origins a browser may call the API from, with the user's credentials.
 *
 * <p>Normally none. The SPA is served by the same nginx as the API, in production and through
 * the Vite proxy in development, so its requests are same-origin and CORS never applies to
 * them; the desktop app loads that same origin, and the requests its main process makes carry
 * no Origin at all. An origin goes in {@code CPINTEL_CORS_ALLOWED_ORIGINS} only when a page
 * served from somewhere else must call the API, and then as an exact origin.
 *
 * <p>This used to allow every origin with credentials. The refresh endpoint answers with a new
 * access token, so any page that could get the refresh cookie sent — another host under the
 * same site, which SameSite does not stop — could read a session out of it.
 *
 * <p>An empty list still registers a configuration rather than none: with nothing registered
 * Spring lets a cross-origin request through to the controller and only the browser's refusal
 * to show the response stands in the way; with an empty one it is answered 403 before that.
 *
 * <p>Entries are exact origins, never patterns. In {@code prod} a malformed one refuses startup,
 * like {@link SecretsCheck}; elsewhere it is dropped with a warning. Neither path ever widens
 * the list.
 */
@Configuration
@Slf4j
public class CorsConfig {

    static final String PROPERTY = "cpintel.cors.allowed-origins";
    private static final String ENV_VAR = "CPINTEL_CORS_ALLOWED_ORIGINS";

    private final Environment env;

    public CorsConfig(Environment env) {
        this.env = env;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        boolean isProd = List.of(env.getActiveProfiles()).contains("prod");
        List<String> origins = allowedOrigins(env.getProperty(PROPERTY, ""), isProd);
        if (!origins.isEmpty()) {
            log.info("CORS: credentialed requests accepted from {}", origins);
        }

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization", "X-Refresh-Token"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    /**
     * The configured origins, each reduced to the form a browser sends in {@code Origin}:
     * lower-case scheme and host, and no port when it is the scheme's default.
     *
     * @throws IllegalStateException in prod, naming every entry that is not an exact
     *                               http(s) origin
     */
    static List<String> allowedOrigins(String raw, boolean isProd) {
        Set<String> origins = new LinkedHashSet<>();
        List<String> problems = new ArrayList<>();
        for (String entry : (raw == null ? "" : raw).split(",")) {
            String value = entry.trim();
            if (value.isEmpty()) continue;
            String problem = problemWith(value);
            if (problem != null) {
                problems.add("'" + value + "': " + problem);
                continue;
            }
            String origin = normalise(value);
            if (isProd && origin.startsWith("http://") && !isLoopback(URI.create(origin).getHost())) {
                log.warn("CORS: {} is plain http, so the token this origin is allowed to read "
                    + "crosses the network unencrypted.", origin);
            }
            origins.add(origin);
        }

        if (!problems.isEmpty()) {
            String detail = String.join("\n  - ", problems);
            if (isProd) {
                throw new IllegalStateException("Refusing to start: " + ENV_VAR + " must list exact "
                    + "origins, scheme://host[:port], separated by commas — or be empty when the "
                    + "frontend and API share nginx.\n  - " + detail);
            }
            log.warn("CORS: ignoring {} entr{} in {} (a prod deployment would refuse to start):"
                + "\n  - {}", problems.size(), problems.size() == 1 ? "y" : "ies", ENV_VAR, detail);
        }
        return List.copyOf(origins);
    }

    /** Why this is not an exact http(s) origin, or null when it is one. */
    private static String problemWith(String value) {
        if (value.contains("*")) return "patterns and wildcards are not accepted";
        if (value.equalsIgnoreCase("null")) {
            return "'null' is the origin of sandboxed frames and local files, never a CPIntel page";
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return "not a URL";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return "only http and https origins";
        if (uri.getRawAuthority() == null || uri.getHost() == null) return "no host";
        if (uri.getRawUserInfo() != null) return "a username or password is not part of an origin";
        if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
            return "an origin has no path, not even a trailing '/'";
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return "an origin has no query or fragment";
        }
        return null;
    }

    private static String normalise(String value) {
        URI uri = URI.create(value);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port == -1
            || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port);
    }

    private static boolean isLoopback(String host) {
        return host.equals("localhost") || host.startsWith("127.") || host.equals("[::1]");
    }
}
