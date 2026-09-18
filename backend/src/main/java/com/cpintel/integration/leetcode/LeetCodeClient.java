package com.cpintel.integration.leetcode;

import com.cpintel.exception.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import com.cpintel.integration.OutboundRateLimiter;
import jakarta.annotation.PostConstruct;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class LeetCodeClient {

    private static final String PLATFORM = "leetcode";
    private static final long MAX_WAIT_MS = 30_000;

    private final WebClient platformWebClient;
    private final OutboundRateLimiter rateLimiter;
    private WebClient client;

    @Value("${cpintel.platforms.leetcode.graphql-url}")
    private String graphqlUrl;

    @Value("${cpintel.platforms.leetcode.rate-limit-ms}")
    private long rateLimitMs;

    @PostConstruct
    void init() {
        client = platformWebClient.mutate()
            .baseUrl(graphqlUrl)
            .defaultHeader("Referer", "https://leetcode.com")
            .build();
    }

    /** Waits for LeetCode's next slot on the shared timeline before handing back the client. */
    private WebClient client() {
        rateLimiter.acquire(PLATFORM, rateLimitMs, MAX_WAIT_MS);
        return client;
    }

    private <T> T query(String query, Map<String, Object> variables, Class<T> type) {

        Map<String, Object> body = variables != null
            ? Map.of("query", query, "variables", variables)
            : Map.of("query", query);

        return client().post()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(type)
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(2)))
            .block(Duration.ofSeconds(20));
    }

    public LcModels.UserProfile getUserProfile(String username) {
        log.debug("Fetching LC profile for: {}", username);
        String gql = """
            query getUserProfile($username: String!) {
              matchedUser(username: $username) {
                username
                submitStats {
                  acSubmissionNum { difficulty count submissions }
                  totalSubmissionNum { difficulty count submissions }
                }
                profile { ranking reputation starRating }
                userCalendar { streak totalActiveDays }
              }
            }
            """;
        LcModels.ProfileResponse resp = query(gql, Map.of("username", username), LcModels.ProfileResponse.class);
        if (resp == null || resp.getData() == null || resp.getData().getMatchedUser() == null)
            throw ApiException.notFound("LeetCode user not found: " + username);
        return resp.getData().getMatchedUser();
    }

    public LcModels.RecentSubmissions getRecentSubmissions(String username, int limit) {
        log.debug("Fetching LC submissions for: {}", username);
        String gql = """
            query recentAcSubmissions($username: String!, $limit: Int!) {
              recentAcSubmissionList(username: $username, limit: $limit) {
                id title titleSlug timestamp lang
              }
            }
            """;
        LcModels.SubmissionListResponse resp = query(gql,
            Map.of("username", username, "limit", limit),
            LcModels.SubmissionListResponse.class);
        return resp != null ? resp.getData() : null;
    }

    public LcModels.ContestRanking getContestRanking(String username) {
        log.debug("Fetching LC contest ranking for: {}", username);
        String gql = """
            query userContestRankingInfo($username: String!) {
              userContestRanking(username: $username) {
                attendedContestsCount rating globalRanking
              }
            }
            """;
        LcModels.ContestRankingResponse resp = query(gql, Map.of("username", username),
            LcModels.ContestRankingResponse.class);
        return resp != null && resp.getData() != null ? resp.getData().getUserContestRanking() : null;
    }

    public boolean handleExists(String username) {
        try {
            getUserProfile(username);
            return true;
        } catch (ApiException e) {
            return false;
        } catch (Exception e) {
            log.warn("LC handle check failed for {}: {}", username, e.getMessage());
            return false;
        }
    }
}
