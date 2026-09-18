package com.cpintel.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rule is narrow and worth stating plainly: prod does not start on a secret somebody could
 * read in this repository, and dev is never blocked by one.
 */
class SecretsCheckTest {

    private static final String GOOD_JWT     = "Zm9yLXRlc3Rpbmctb25seS1sb25nLWVub3VnaC1rZXk";
    private static final String GOOD_SESSION = "another-testing-key-value";
    private static final String GOOD_MONGO   = "mongodb://u:realpassword@localhost:27017/cpintel";

    private MockEnvironment env(String... profiles) {
        MockEnvironment e = new MockEnvironment();
        e.setActiveProfiles(profiles);
        Map.of(
            "jwt.secret",                    GOOD_JWT,
            "cpintel.practice.session-key",  GOOD_SESSION,
            "spring.datasource.password",    "realpassword",
            "spring.data.redis.password",    "realpassword",
            "spring.data.mongodb.uri",       GOOD_MONGO
        ).forEach(e::setProperty);
        return e;
    }

    private void verify(MockEnvironment e) {
        new SecretsCheck(e).verify();
    }

    @Nested
    @DisplayName("Under the prod profile")
    class Prod {

        @Test
        @DisplayName("a fully configured deployment starts")
        void goodConfigStarts() {
            assertDoesNotThrow(() -> verify(env("prod")));
        }

        @Test
        @DisplayName("a missing JWT secret refuses startup and names the variable")
        void missingJwtSecret() {
            MockEnvironment e = env("prod");
            e.setProperty("jwt.secret", "");

            var ex = assertThrows(IllegalStateException.class, () -> verify(e));
            assertTrue(ex.getMessage().contains("JWT_SECRET"), ex.getMessage());
        }

        @Test
        @DisplayName("the published placeholder is refused even though it is long enough")
        void publishedPlaceholderRefused() {
            MockEnvironment e = env("prod");
            e.setProperty("jwt.secret",
                "cpintel_super_secret_jwt_key_change_this_in_production_min64chars_ok");

            var ex = assertThrows(IllegalStateException.class, () -> verify(e));
            assertTrue(ex.getMessage().contains("placeholder"), ex.getMessage());
        }

        @Test
        @DisplayName("a JWT secret too short for HS256 is caught here, not at first token mint")
        void shortJwtSecretRefused() {
            MockEnvironment e = env("prod");
            e.setProperty("jwt.secret", "tooshort");

            var ex = assertThrows(IllegalStateException.class, () -> verify(e));
            assertTrue(ex.getMessage().contains("at least 32"), ex.getMessage());
        }

        @Test
        @DisplayName("an empty Mongo password is spotted inside the URI")
        void emptyMongoPasswordRefused() {
            MockEnvironment e = env("prod");
            e.setProperty("spring.data.mongodb.uri", "mongodb://cpintel_mongo:@mongodb:27017/cpintel");

            var ex = assertThrows(IllegalStateException.class, () -> verify(e));
            assertTrue(ex.getMessage().contains("MONGO_PASSWORD"), ex.getMessage());
        }

        @Test
        @DisplayName("every problem is reported at once, not one restart at a time")
        void reportsAllProblemsTogether() {
            MockEnvironment e = env("prod");
            e.setProperty("jwt.secret", "");
            e.setProperty("cpintel.practice.session-key", "");
            e.setProperty("spring.datasource.password", "");
            e.setProperty("spring.data.redis.password", "");

            var ex = assertThrows(IllegalStateException.class, () -> verify(e));
            String msg = ex.getMessage();
            assertTrue(msg.contains("JWT_SECRET"), msg);
            assertTrue(msg.contains("CPINTEL_SESSION_KEY"), msg);
            assertTrue(msg.contains("POSTGRES_PASSWORD"), msg);
            assertTrue(msg.contains("REDIS_PASSWORD"), msg);
        }
    }

    @Nested
    @DisplayName("Outside prod")
    class NonProd {

        @Test
        @DisplayName("the same broken config warns and still starts")
        void devStartsAnyway() {
            MockEnvironment e = env("dev");
            e.setProperty("jwt.secret", "");
            e.setProperty("spring.datasource.password", "");

            assertDoesNotThrow(() -> verify(e));
        }

        @Test
        @DisplayName("a deployment with no profile set is not treated as prod")
        void noProfileIsNotProd() {
            MockEnvironment e = env();
            e.setProperty("jwt.secret", "");

            assertDoesNotThrow(() -> verify(e));
        }
    }
}
