package com.cpintel.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
    /**
     * Guesses at an examination password, per candidate.
     *
     * Tighter than the others, because the thing being guessed at is short enough to be worth
     * guessing at, and because a candidate reading a code off a slip on their own desk does
     * not need ten attempts. Generous enough for somebody who mistypes it twice and cannot
     * read their own handwriting the third time.
     */
    private Rule examUnlock = new Rule(10, Duration.ofMinutes(5));

    /**
     * Address ranges many people share through one NAT — a lab whose two hundred machines reach
     * the server as a single address. CIDR notation, e.g. {@code 10.20.0.0/16}.
     *
     * <p>The per-address limits (sign-in, account recovery) exist to stop one machine guessing
     * passwords. Applied to a NATed lab they stop the whole room signing in when a paper opens,
     * because the room is one address. Inside these ranges the per-address limit becomes
     * {@link #sharedLogin} instead. The per-account limit still applies to everybody, so guessing
     * at one person's password is exactly as slow from a lab as from anywhere else.
     */
    private List<String> sharedNetworks = new ArrayList<>();

    /** The per-address sign-in and recovery allowance inside {@link #sharedNetworks}. */
    private Rule sharedLogin = new Rule(600, Duration.ofMinutes(1));

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
