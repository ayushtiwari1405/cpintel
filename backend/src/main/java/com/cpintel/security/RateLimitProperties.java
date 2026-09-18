package com.cpintel.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * How much of each expensive thing one caller may have, and how often.
 *
 * <p>The numbers are deliberately loose enough that no honest use hits them. A person signing in
 * gets it wrong twice and retries; they do not make ten attempts in a minute. Someone doing that
 * is guessing, and the limit exists for them.
 */
@Component
@ConfigurationProperties(prefix = "cpintel.rate-limit")
@Getter
@Setter
public class RateLimitProperties {

    /** Master switch. Off disables every limit — intended for tests, not deployments. */
    private boolean enabled = true;

    private Rule login    = new Rule(20, Duration.ofMinutes(1));
    private Rule account  = new Rule(5,  Duration.ofMinutes(5));
    private Rule recovery = new Rule(5,  Duration.ofMinutes(15));
    private Rule run      = new Rule(30, Duration.ofMinutes(1));
    private Rule sync     = new Rule(5,  Duration.ofMinutes(5));

    @Getter
    @Setter
    public static class Rule {
        private int limit;
        private Duration window;

        public Rule() {}

        public Rule(int limit, Duration window) {
            this.limit = limit;
            this.window = window;
        }
    }
}
