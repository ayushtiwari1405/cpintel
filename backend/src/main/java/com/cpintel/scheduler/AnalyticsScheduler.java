package com.cpintel.scheduler;

import com.cpintel.analytics.AnalyticsService;
import com.cpintel.analytics.OracleProcedureCaller;
import com.cpintel.repository.jpa.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AnalyticsScheduler {

    private final UserRepository userRepository;
    private final AnalyticsService analyticsService;
    private final OracleProcedureCaller oracle;

    /** Refresh all user analytics nightly at 04:00 UTC */
    @Scheduled(cron = "0 0 4 * * *", zone = "UTC")
    public void refreshAllAnalytics() {
        log.info("Starting nightly analytics refresh");
        var users = userRepository.findAll();
        int success = 0, failed = 0;
        for (var user : users) {
            try {
                analyticsService.updateTopicMasteryFromSubmissions(user.getUserId());
                success++;
            } catch (Exception e) {
                failed++;
                log.warn("Analytics refresh failed for user {}: {}", user.getUserId(), e.getMessage());
            }
        }
        log.info("Nightly analytics done. success={} failed={}", success, failed);
    }

    /** Update unified scores every 6 hours */
    @Scheduled(fixedRate = 6 * 60 * 60 * 1000)
    public void refreshUnifiedScores() {
        log.debug("Refreshing unified scores");
        try {
            oracle.updateAllScores();
        } catch (Exception e) {
            log.error("Unified score refresh failed: {}", e.getMessage());
        }
    }
}
