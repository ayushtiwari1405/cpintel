package com.cpintel.scheduler;

import com.cpintel.analytics.AnalyticsService;
import com.cpintel.analytics.MaterializedViewRefresher;
import com.cpintel.analytics.UnifiedRatingService;
import com.cpintel.repository.jpa.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AnalyticsScheduler {

    /** Big enough to keep the round trips down, small enough to stay bounded. */
    private static final int BATCH_SIZE = 200;

    private final UserRepository userRepository;
    private final AnalyticsService analyticsService;
    private final UnifiedRatingService unifiedRatingService;
    private final MaterializedViewRefresher materializedViewRefresher;

    /** Refresh all user analytics nightly at 04:00 UTC */
    @Scheduled(cron = "0 0 4 * * *", zone = "UTC")
    @SchedulerLock(name = "refreshAllAnalytics", lockAtMostFor = "PT3H", lockAtLeastFor = "PT1M")
    public void refreshAllAnalytics() {
        log.info("Starting nightly analytics refresh");

        int success = 0, failed = 0, page = 0;
        Slice<Long> batch;

        // Paged rather than findAll().
        //
        // Loading every user as a managed entity put the whole table in heap before the first
        // one was processed, against a 1 GB container limit, and grew with every registration.
        // Only the ids are needed here — the service loads what it works on — so the footprint
        // is now a page of longs regardless of how large the deployment gets.
        do {
            batch = userRepository.findAllUserIds(PageRequest.of(page++, BATCH_SIZE));
            for (Long userId : batch) {
                try {
                    analyticsService.updateTopicMasteryFromSubmissions(userId);
                    success++;
                } catch (Exception e) {
                    // Per-user isolation, kept: one unparseable submission history should not
                    // stop the other nine thousand accounts being refreshed.
                    failed++;
                    log.warn("Analytics refresh failed for user {}: {}", userId, e.getMessage());
                }
            }
        } while (batch.hasNext());

        log.info("Nightly analytics done. success={} failed={}", success, failed);
    }

    /** Update unified scores every 6 hours */
    @Scheduled(fixedRate = 6 * 60 * 60 * 1000)
    @SchedulerLock(name = "refreshUnifiedScores", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void refreshUnifiedScores() {
        log.debug("Refreshing unified scores");
        try {
            unifiedRatingService.updateAllScores();
        } catch (Exception e) {
            log.error("Unified score refresh failed: {}", e.getMessage());
        }
    }

    /**
     * Rebuild the reporting materialized views nightly at 05:00 UTC, after the 04:00
     * analytics pass has written the rows they read. This was the JOB_REFRESH_MVS
     * DBMS_SCHEDULER job; Postgres has no in-database scheduler, so it lives here.
     */
    @Scheduled(cron = "0 0 5 * * *", zone = "UTC")
    @SchedulerLock(name = "refreshMaterializedViews", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void refreshMaterializedViews() {
        materializedViewRefresher.refreshAll();
    }
}
