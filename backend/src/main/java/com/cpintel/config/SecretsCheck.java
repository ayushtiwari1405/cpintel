package com.cpintel.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Refuses to start a production deployment that is running on a missing or placeholder secret.
 *
 * <p>Every credential in {@code application.yml} used to carry a working value as its default,
 * so a deploy that forgot an environment variable booted successfully and silently — in the
 * case of {@code jwt.secret}, signing tokens with a key published in the repository. Anyone
 * holding it could mint a valid SUPER_ADMIN token. The defaults are gone; this is what makes
 * their absence loud instead of subtle.
 *
 * <p>It runs from {@code @PostConstruct} rather than as an {@code ApplicationRunner} on
 * purpose. A runner executes after the context is refreshed and the connector is already
 * accepting traffic, which is too late to call it failing closed; throwing here aborts the
 * refresh before anything is listening.
 *
 * <p>Outside {@code prod} the same problems are reported as warnings and startup continues,
 * because a developer with a half-filled {@code .env} should get a readable explanation rather
 * than a refusal — and because dev has nothing worth protecting.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SecretsCheck {

    /** HS256 will not accept a key shorter than this, and should not. */
    private static final int MIN_JWT_SECRET_BYTES = 32;

    /** Shorter than a JWT key, but still long enough not to be guessed. */
    private static final int MIN_SESSION_KEY_BYTES = 16;

    /**
     * The values that used to ship as defaults.
     *
     * Listed so that copying an old {@code application.yml} value into {@code .env} — the most
     * likely way this mistake survives the change — is caught rather than accepted. Publishing
     * them here costs nothing: they are already in the repository's history, and the point is
     * to make them unusable rather than secret.
     */
    private static final Set<String> KNOWN_PLACEHOLDERS = Set.of(
        "cpintel_super_secret_jwt_key_change_this_in_production_min64chars_ok",
        "cpintel_dev_session_key_change_me_immediately",
        "CPIntelApp#2025",
        "CPIntelRedis#2025",
        "CPIntelMongo#2025",
        "changeme",
        "change_me",
        "secret"
    );

    private final Environment env;

    @PostConstruct
    void verify() {
        boolean isProd = List.of(env.getActiveProfiles()).contains("prod");

        List<String> problems = new ArrayList<>();

        check(problems, "JWT_SECRET", "jwt.secret", MIN_JWT_SECRET_BYTES);
        check(problems, "CPINTEL_SESSION_KEY", "cpintel.practice.session-key", MIN_SESSION_KEY_BYTES);
        check(problems, "POSTGRES_PASSWORD", "spring.datasource.password", 1);
        check(problems, "REDIS_PASSWORD", "spring.data.redis.password", 1);
        checkMongoUri(problems);

        if (problems.isEmpty()) {
            log.debug("Secrets check passed ({} profile)", isProd ? "prod" : "non-prod");
            return;
        }

        String detail = String.join("\n  - ", problems);

        if (isProd) {
            throw new IllegalStateException(
                "Refusing to start: this deployment is running on missing or placeholder "
                + "secrets.\n  - " + detail
                + "\n\nSet these in the environment and restart. See \"Roles and accounts\" and "
                + "the configuration table in README.md.");
        }

        log.warn("Secrets check found {} problem(s). Startup continues because this is not the "
            + "prod profile, but a prod deployment would refuse to start:\n  - {}",
            problems.size(), detail);
    }

    /**
     * Reports every problem it finds rather than the first.
     *
     * Someone fixing a deployment at the point it refuses to boot should learn about all four
     * missing variables at once, not discover them one restart at a time.
     */
    private void check(List<String> problems, String envVar, String property, int minBytes) {
        String value = env.getProperty(property);

        if (value == null || value.isBlank()) {
            problems.add(envVar + " is not set (property " + property + ")");
            return;
        }
        if (KNOWN_PLACEHOLDERS.contains(value)) {
            problems.add(envVar + " is still set to a known placeholder value — it is published "
                + "in this repository and must be replaced");
            return;
        }
        if (value.length() < minBytes) {
            problems.add(envVar + " is " + value.length() + " characters; at least " + minBytes
                + " are required");
        }
    }

    /**
     * The Mongo credentials arrive inside a URI, so they cannot be length-checked like the rest.
     * An empty password leaves the tell-tale {@code :@} between the userinfo and the host.
     */
    private void checkMongoUri(List<String> problems) {
        String uri = env.getProperty("spring.data.mongodb.uri");

        if (uri == null || uri.isBlank()) {
            problems.add("SPRING_DATA_MONGODB_URI is not set");
            return;
        }
        if (uri.contains(":@")) {
            problems.add("MONGO_PASSWORD is empty — the Mongo URI has no password between the "
                + "user and the host");
        }
        for (String placeholder : KNOWN_PLACEHOLDERS) {
            if (uri.contains(placeholder)) {
                problems.add("The Mongo URI contains the published placeholder password "
                    + "and must be replaced");
                return;
            }
        }
    }
}
