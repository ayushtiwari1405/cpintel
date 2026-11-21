package com.cpintel.integration.codeforces;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.cpintel.integration.OutboundRateLimiter;
import jakarta.annotation.PostConstruct;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Component
@RequiredArgsConstructor
@Slf4j
public class CfProblemsetClient {

    // Shares codeforces' timeline: this hits the same host and the same quota.
    private static final String PLATFORM = "codeforces";
    private static final long MAX_WAIT_MS = 30_000;

    private final WebClient platformWebClient;
    private final OutboundRateLimiter rateLimiter;
    private WebClient client;

    @Value("${cpintel.platforms.codeforces.rate-limit-ms}")
    private long rateLimitMs;

    @Value("${cpintel.platforms.codeforces.base-url}")
    private String baseUrl;

    private final AtomicReference<List<CfModels.Submission.Problem>> cache = new AtomicReference<>(List.of());
    private volatile Instant cachedAt = Instant.EPOCH;
    private static final Duration TTL = Duration.ofHours(6);
    /**
     * After a failed refresh, how long before asking Codeforces again. Without it every request
     * for a problem list waited out its own retries against a site that was not answering,
     * one after another, and the roadmap hung for as long as Codeforces was down.
     */
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(2);
    private volatile Instant retryNotBefore = Instant.EPOCH;
    /** First wait between the two quick retries inside one refresh; tests shorten it. */
    private Duration retryBackoff = Duration.ofSeconds(2);

    @PostConstruct
    void init() {
        client = platformWebClient.mutate().baseUrl(baseUrl).build();
    }

    public synchronized List<CfModels.Submission.Problem> getAllProblems() {
        if (Instant.now().isBefore(cachedAt.plus(TTL)) && !cache.get().isEmpty()) {
            return cache.get();
        }
        if (Instant.now().isBefore(retryNotBefore)) return cache.get();
        try {
            rateLimiter.acquire(PLATFORM, rateLimitMs, MAX_WAIT_MS);
            CfProblemsetResponse resp = client
                .get().uri("/problemset.problems")
                .retrieve()
                .bodyToMono(CfProblemsetResponse.class)
                .retryWhen(Retry.backoff(2, retryBackoff))
                .block(Duration.ofSeconds(20));

            if (resp != null && resp.getResult() != null && resp.getResult().getProblems() != null) {
                cache.set(resp.getResult().getProblems());
                cachedAt = Instant.now();
                retryNotBefore = Instant.EPOCH;
                log.info("Cached {} CF problems", cache.get().size());
            } else {
                retryNotBefore = Instant.now().plus(RETRY_AFTER_FAILURE);
                log.warn("Codeforces answered the problem list with no problems; keeping {} cached",
                    cache.get().size());
            }
        } catch (Exception e) {
            retryNotBefore = Instant.now().plus(RETRY_AFTER_FAILURE);
            log.warn("Failed to refresh CF problemset cache ({} cached kept, next try in {}): {}",
                cache.get().size(), RETRY_AFTER_FAILURE, e.getMessage());
        }
        return cache.get().isEmpty() ? Collections.emptyList() : cache.get();
    }
}
