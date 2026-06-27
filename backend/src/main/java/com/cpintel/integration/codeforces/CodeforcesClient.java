package com.cpintel.integration.codeforces;

import com.cpintel.exception.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class CodeforcesClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${cpintel.platforms.codeforces.base-url}")
    private String baseUrl;

    @Value("${cpintel.platforms.codeforces.rate-limit-ms}")
    private long rateLimitMs;

    private WebClient client() {
        return webClientBuilder.baseUrl(baseUrl).build();
    }

    public CfUserInfoResponse getUserInfo(String handle) {
        log.debug("Fetching CF user info for: {}", handle);
        return client().get()
            .uri("/user.info?handles={handle}", handle)
            .retrieve()
            .bodyToMono(CfUserInfoResponse.class)
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(2)))
            .block(Duration.ofSeconds(15));
    }

    public CfRatingResponse getRatingHistory(String handle) {
        log.debug("Fetching CF rating history for: {}", handle);
        return client().get()
            .uri("/user.rating?handle={handle}", handle)
            .retrieve()
            .bodyToMono(CfRatingResponse.class)
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(2)))
            .block(Duration.ofSeconds(15));
    }

    public CfSubmissionsResponse getSubmissions(String handle, int from, int count) {
        log.debug("Fetching CF submissions for: {} from={} count={}", handle, from, count);
        try {
            Thread.sleep(rateLimitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return client().get()
            .uri("/user.status?handle={handle}&from={from}&count={count}", handle, from, count)
            .retrieve()
            .onStatus(status -> status.value() == 400,
                resp -> Mono.error(ApiException.badRequest("CF handle not found: " + handle)))
            .bodyToMono(CfSubmissionsResponse.class)
            .retryWhen(Retry.backoff(2, Duration.ofSeconds(3)))
            .block(Duration.ofSeconds(20));
    }

    public CfContestListResponse getContestList() {
        return client().get()
            .uri("/contest.list?gym=false")
            .retrieve()
            .bodyToMono(CfContestListResponse.class)
            .block(Duration.ofSeconds(15));
    }

    public boolean handleExists(String handle) {
        try {
            CfUserInfoResponse r = getUserInfo(handle);
            return r != null && "OK".equals(r.getStatus());
        } catch (Exception e) {
            return false;
        }
    }
}
