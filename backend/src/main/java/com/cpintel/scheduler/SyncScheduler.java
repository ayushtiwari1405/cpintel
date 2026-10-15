package com.cpintel.scheduler;

import com.cpintel.repository.jpa.PlatformAccountRepository;
import com.cpintel.repository.jpa.RefreshTokenRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import com.cpintel.service.SyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class SyncScheduler {

    private static final int BATCH_SIZE = 200;

    private final PlatformAccountRepository platformAccountRepository;
    private final SyncService syncService;
    private final RefreshTokenRepository refreshTokenRepository;

    /**
     * Nightly incremental sync for all linked accounts.
     * Runs at 02:00 UTC every day.
     */
    @Scheduled(cron = "0 0 2 * * *", zone = "UTC")
    @SchedulerLock(name = "nightlySync", lockAtMostFor = "PT4H", lockAtLeastFor = "PT1M")
    public void nightlySync() {
        log.info("Starting nightly sync job");

        int count = 0, page = 0;
        Slice<PlatformAccountRepository.ActiveAccount> batch;

        // Paged, and filtered in the query rather than in the loop: the old version pulled every
        // linked account into heap and then skipped the inactive ones, doing the most expensive
        // part of the work for rows it was about to discard.
        do {
            batch = platformAccountRepository.findActiveAccounts(PageRequest.of(page++, BATCH_SIZE));
            for (var account : batch) {
                try {
                    // On this thread, one after another — see SyncService.syncNow.
                    syncService.syncNow(account.getUserId(), account.getPlatform(), "INCREMENTAL");
                    count++;
                } catch (com.cpintel.exception.ApiException e) {
                    // Already syncing — somebody pressed Sync — which is this job's work done.
                    log.debug("Nightly sync skipped account {}: {}", account.getAccountId(),
                        e.getMessage());
                } catch (Exception e) {
                    log.warn("Nightly sync failed for account {}: {}",
                        account.getAccountId(), e.getMessage());
                }
            }
        } while (batch.hasNext());

        log.info("Nightly sync finished for {} accounts", count);
    }

    /**
     * Deletes refresh tokens past their expiry, every 6 hours.
     *
     * <p>This used to log and do nothing else, so the table grew by a row on every sign-in and
     * every renewal, for ever; an expired row went only when somebody happened to present it.
     * Revoked rows that have not yet expired are kept on purpose: presenting one of those again
     * is how a stolen token is recognised (see {@code AuthService.refresh}), and that can only
     * happen until it would have expired anyway.
     */
    @Scheduled(fixedRate = 6 * 60 * 60 * 1000)
    // lockAtLeastFor matters more than lockAtMostFor here.
    //
    // ShedLock frees a lock as soon as the method returns, and this one finishes in
    // microseconds — so with only lockAtMostFor set, a second instance starting moments later
    // found the lock already released and ran the job again. Holding it for a floor period is
    // what actually prevents a double run of a fast job across instances whose clocks and
    // start times are not identical.
    @SchedulerLock(name = "cleanExpiredTokens", lockAtMostFor = "PT15M", lockAtLeastFor = "PT5M")
    @Transactional
    public void cleanExpiredTokens() {
        int deleted = refreshTokenRepository.deleteExpiredTokens(Instant.now());
        log.debug("Deleted {} expired refresh tokens", deleted);
    }
}
