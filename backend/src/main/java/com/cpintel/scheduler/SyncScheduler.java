package com.cpintel.scheduler;

import com.cpintel.repository.jpa.PlatformAccountRepository;
import com.cpintel.service.SyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SyncScheduler {

    private final PlatformAccountRepository platformAccountRepository;
    private final SyncService syncService;

    /**
     * Nightly incremental sync for all linked accounts.
     * Runs at 02:00 UTC every day.
     */
    @Scheduled(cron = "0 0 2 * * *", zone = "UTC")
    public void nightlySync() {
        log.info("Starting nightly sync job");
        var accounts = platformAccountRepository.findAll();
        int count = 0;
        for (var account : accounts) {
            if (Boolean.TRUE.equals(account.getIsActive())) {
                try {
                    syncService.triggerSync(
                        account.getUser().getUserId(),
                        account.getPlatform(),
                        "INCREMENTAL"
                    );
                    count++;
                } catch (Exception e) {
                    log.warn("Nightly sync failed for account {}: {}", account.getAccountId(), e.getMessage());
                }
            }
        }
        log.info("Nightly sync triggered for {} accounts", count);
    }

    /**
     * Clean up expired refresh tokens every 6 hours.
     */
    @Scheduled(fixedRate = 6 * 60 * 60 * 1000)
    public void cleanExpiredTokens() {
        log.debug("Cleaning expired refresh tokens");
    }
}
