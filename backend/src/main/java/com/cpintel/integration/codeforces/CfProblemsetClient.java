package com.cpintel.integration.codeforces;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
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

    private final WebClient.Builder webClientBuilder;

    @Value("${cpintel.platforms.codeforces.base-url}")
    private String baseUrl;

    private final AtomicReference<List<CfModels.Submission.Problem>> cache = new AtomicReference<>(List.of());
    private volatile Instant cachedAt = Instant.EPOCH;
    private static final Duration TTL = Duration.ofHours(6);

    public synchronized List<CfModels.Submission.Problem> getAllProblems() {
        if (Instant.now().isBefore(cachedAt.plus(TTL)) && !cache.get().isEmpty()) {
            return cache.get();
        }
        try {
            CfProblemsetResponse resp = webClientBuilder.baseUrl(baseUrl).build()
                .get().uri("/problemset.problems")
                .retrieve()
                .bodyToMono(CfProblemsetResponse.class)
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(2)))
                .block(Duration.ofSeconds(20));

            if (resp != null && resp.getResult() != null && resp.getResult().getProblems() != null) {
                cache.set(resp.getResult().getProblems());
                cachedAt = Instant.now();
                log.info("Cached {} CF problems", cache.get().size());
            }
        } catch (Exception e) {
            log.warn("Failed to refresh CF problemset cache: {}", e.getMessage());
        }
        return cache.get().isEmpty() ? Collections.emptyList() : cache.get();
    }
}
