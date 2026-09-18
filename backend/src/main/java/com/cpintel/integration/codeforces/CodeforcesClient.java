package com.cpintel.integration.codeforces;

import com.cpintel.exception.ApiException;
import com.cpintel.config.AppMetrics;
import com.cpintel.integration.OutboundRateLimiter;
import jakarta.annotation.PostConstruct;
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

    /** Named so every call to this judge queues on one timeline, whatever thread it is on. */
    private static final String PLATFORM = "codeforces";

    /** A background sync can afford to queue; nothing here should hold a thread past this. */
    private static final long MAX_WAIT_MS = 30_000;

    private final WebClient platformWebClient;
    private final OutboundRateLimiter rateLimiter;
    private final AppMetrics metrics;

    @Value("${cpintel.platforms.codeforces.base-url}")
    private String baseUrl;

    @Value("${cpintel.platforms.codeforces.rate-limit-ms}")
    private long rateLimitMs;

    private WebClient client;

    @PostConstruct
    void init() {
        // Built once. The base URL is a property, so this cannot be a bean without splitting
        // the config across two files for no gain.
        client = platformWebClient.mutate().baseUrl(baseUrl).build();
    }

    /**
     * Waits for this judge's next slot.
     *
     * Every outbound call goes through here, not just the ones that used to sleep. The old code
     * paced only {@code getSubmissions}, which left the rating and contest endpoints unpaced
     * and counting against the same quota.
     */
    private WebClient client() {
        rateLimiter.acquire(PLATFORM, rateLimitMs, MAX_WAIT_MS);
        return client;
    }

    /**
     * Times one call to the judge and records whether it answered.
     *
     * Wrapping at this level rather than annotating each method keeps the operation tag a fixed
     * set of names chosen here, instead of whatever the method happened to be called.
     */
    private <T> T timed(String operation, java.util.function.Supplier<T> call) {
        long started = System.nanoTime();
        boolean ok = false;
        try {
            T result = call.get();
            ok = true;
            return result;
        } finally {
            metrics.platformCall(PLATFORM, operation, ok,
                java.time.Duration.ofNanos(System.nanoTime() - started));
        }
    }

    public CfUserInfoResponse getUserInfo(String handle) {
        log.debug("Fetching CF user info for: {}", handle);
        return timed("user.info", () -> client().get()
            .uri("/user.info?handles={handle}", handle)
            .retrieve()
            .bodyToMono(CfUserInfoResponse.class)
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(2)))
            .block(Duration.ofSeconds(15)));
    }

    public CfRatingResponse getRatingHistory(String handle) {
        log.debug("Fetching CF rating history for: {}", handle);
        return timed("user.rating", () -> client().get()
            .uri("/user.rating?handle={handle}", handle)
            .retrieve()
            .bodyToMono(CfRatingResponse.class)
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(2)))
            .block(Duration.ofSeconds(15)));
    }

    public CfSubmissionsResponse getSubmissions(String handle, int from, int count) {
        log.debug("Fetching CF submissions for: {} from={} count={}", handle, from, count);
        return timed("user.status", () -> client().get()
            .uri("/user.status?handle={handle}&from={from}&count={count}", handle, from, count)
            .retrieve()
            .onStatus(status -> status.value() == 400,
                resp -> Mono.error(ApiException.badRequest("CF handle not found: " + handle)))
            .bodyToMono(CfSubmissionsResponse.class)
            .retryWhen(Retry.backoff(2, Duration.ofSeconds(3)))
            .block(Duration.ofSeconds(20)));
    }

    public CfContestListResponse getContestList() {
        return client().get()
            .uri("/contest.list?gym=false")
            .retrieve()
            .bodyToMono(CfContestListResponse.class)
            .block(Duration.ofSeconds(15));
    }

    /**
     * Metadata for one contest: name, phase, official start and duration.
     *
     * Deliberately NOT contest.standings. Codeforces restricts that endpoint for non-gym
     * contests to anonymous calls with no extra parameters — passing handles or showUnofficial
     * fails outright — and the unrestricted form returns the entire ranklist, which was 8.9 MB
     * and 11k rows for one finished Div. 4. contest.list is the cheap, allowed way to ask.
     *
     * Note this describes the contest, not the viewer: during virtual participation the phase
     * stays FINISHED while the user's own window is running. The pages settle that question.
     */
    public CfStandingsResponse.Contest getContestMeta(int contestId) {
        log.debug("Fetching CF contest metadata for {}", contestId);
        CfContestMetaResponse resp = client().get()
            .uri("/contest.list?gym=false")
            .retrieve()
            .bodyToMono(CfContestMetaResponse.class)
            .retryWhen(Retry.backoff(2, Duration.ofSeconds(3)))
            .block(Duration.ofSeconds(20));

        if (resp == null || resp.getResult() == null) return null;
        return resp.getResult().stream()
            .filter(c -> c.getId() != null && c.getId() == contestId)
            .findFirst()
            .orElse(null);
    }

    /** Every submission this handle has made in one contest, newest first. */
    public CfSubmissionsResponse getContestStatus(int contestId, String handle) {
        log.debug("Fetching CF contest status for contest {} handle {}", contestId, handle);
        throttle();
        return client().get()
            .uri("/contest.status?contestId={id}&handle={handle}&from=1&count=200",
                contestId, handle)
            .retrieve()
            .onStatus(status -> status.value() == 400,
                resp -> Mono.error(ApiException.badRequest(
                    "Codeforces refused the submission list for contest " + contestId)))
            .bodyToMono(CfSubmissionsResponse.class)
            .retryWhen(Retry.backoff(2, Duration.ofSeconds(3)))
            .block(Duration.ofSeconds(20));
    }

    private void throttle() {
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
