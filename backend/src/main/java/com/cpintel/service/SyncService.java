package com.cpintel.service;

import com.cpintel.dto.PlatformDto;
import com.cpintel.entity.PlatformAccount;
import com.cpintel.entity.SyncJob;
import com.cpintel.entity.User;
import com.cpintel.entity.mongo.CfSubmission;
import com.cpintel.exception.ApiException;
import com.cpintel.integration.PlatformNormalizer;
import com.cpintel.integration.codeforces.CfModels;
import com.cpintel.integration.codeforces.CodeforcesClient;
import com.cpintel.repository.jpa.*;
import com.cpintel.repository.jpa.ContestSummaryRepository;
import com.cpintel.repository.mongo.*;
import com.cpintel.config.AppMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.concurrent.RejectedExecutionException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class SyncService {

    private final AppMetrics metrics;
    private final UserRepository userRepository;
    private final PlatformAccountRepository platformAccountRepository;
    private final SyncJobRepository syncJobRepository;
    private final CfSubmissionRepository cfSubmissionRepository;
    private final ContestSummaryRepository contestSummaryRepository;
    private final CodeforcesClient cfClient;
    private final PlatformNormalizer normalizer;
    private final ContestSyncService contestSyncService;
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    private final org.springframework.beans.factory.ObjectProvider<com.cpintel.analytics.AnalyticsService> analytics;

    /**
     * This service through its proxy. {@code @Async} is applied by the proxy, so calling
     * {@link #runSyncAsync} on {@code this} ran the whole sync on the request thread — a link
     * waited for every submission and every contest to be fetched, and the bounded queue that
     * is meant to refuse work when full was never consulted.
     */
    @Lazy
    @Autowired
    private SyncService self;

    private static final int CF_BATCH = 200;

    /**
     * A sync marked in flight for longer than this is taken to have died with its backend. A
     * full sync of a long history is minutes, bounded by Codeforces' rate limit.
     */
    static final java.time.Duration ABANDONED_AFTER = java.time.Duration.ofHours(1);

    @Transactional
    public PlatformDto.Summary linkAccount(Long userId, String platform, String handle) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> ApiException.notFound("User not found"));

        if (platformAccountRepository.existsByUserUserIdAndPlatform(userId, platform))
            throw ApiException.conflict(platform + " account already linked");

        // A handle credits its results on classroom boards, so one belongs to one account.
        if (platformAccountRepository.existsByPlatformAndHandleIgnoreCaseAndUserUserIdNot(platform, handle.trim(), userId))
            throw ApiException.conflict("That " + platform + " handle is already linked to another account.");

        // Validate handle exists on the platform
        validateHandle(platform, handle);

        PlatformAccount account = PlatformAccount.builder()
            .user(user)
            .platform(platform)
            .handle(handle)
            .syncStatus("PENDING")
            .isActive(true)
            .build();

        account = platformAccountRepository.save(account);
        log.info("Linked {} account '{}' for user {}", platform, handle, userId);

        // Kick off initial sync
        triggerSync(userId, platform, "FULL");

        return mapToSummary(account);
    }

    @Transactional
    public void unlinkAccount(Long userId, String platform) {
        PlatformAccount account = platformAccountRepository
            .findByUserUserIdAndPlatform(userId, platform)
            .orElseThrow(() -> ApiException.notFound(platform + " account not linked"));

        // Wipe all derived data tied to this platform for this user so a later
        // re-link with a different handle never inherits stale contest/submission
        // history from the previous handle.
        contestSummaryRepository.deleteByUserUserIdAndPlatform(userId, platform);

        if ("CODEFORCES".equals(platform)) cfSubmissionRepository.deleteByUserId(userId);

        platformAccountRepository.delete(account);
        log.info("Unlinked {} for user {} and purged derived data", platform, userId);
    }

    @Transactional
    public PlatformDto.SyncResponse triggerSync(Long userId, String platform, String jobType) {
        QueuedJob queued = createJob(userId, platform, jobType);
        Long jobId = queued.jobId();
        String finalJobType = jobType;

        // Run async.
        //
        // The executor is deliberately bounded, so this can be refused when the queue is full.
        // Left to propagate, the rejection reached the client as a 500 — "the server is broken"
        // when the truth is "come back shortly". The job row is marked so it is not left showing
        // QUEUED forever with nothing working on it.
        Long accountId = queued.accountId();
        Runnable dispatch = () -> {
            try {
                self.runSyncAsync(jobId, accountId, userId, platform, finalJobType);
            } catch (RejectedExecutionException e) {
                syncJobRepository.markFailed(jobId, "The sync queue is full. Try again shortly.",
                    Instant.now());
                metrics.syncRejected(platform);
                log.warn("Sync queue full — refused a {} sync for user {}", platform, userId);
                throw ApiException.serviceUnavailable(
                    "Too many syncs are already running. Try again in a minute.");
            }
        };

        // Handed over only once the job row is committed. The sync thread starts by reading
        // that row, and before the commit it would find nothing and quietly do no work.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        dispatch.run();
                    }
                });
        } else {
            dispatch.run();
        }

        return PlatformDto.SyncResponse.builder()
            .jobId(jobId)
            .platform(platform)
            .status("QUEUED")
            .message("Sync started. Check status with /api/integrations/" + platform.toLowerCase() + "/status")
            .build();
    }

    /** A sync job row, written and committed, and the account it is for. */
    public record QueuedJob(Long jobId, Long accountId) {}

    /** Writes the job row a sync reports into. Its own transaction when called through the proxy. */
    @Transactional
    public QueuedJob createJob(Long userId, String platform, String jobType) {
        PlatformAccount account = platformAccountRepository
            .findByUserUserIdAndPlatform(userId, platform)
            .orElseThrow(() -> ApiException.notFound(platform + " account not linked"));

        User user = userRepository.findById(userId)
            .orElseThrow(() -> ApiException.notFound("User not found"));

        // One at a time per account. A job that has shown no sign of life for an hour belongs to
        // a backend that stopped mid-sync; it is closed rather than left to block this account.
        Instant now = Instant.now();
        for (SyncJob inFlight : syncJobRepository.findInFlight(userId, platform)) {
            Instant since = inFlight.getStartedAt() != null ? inFlight.getStartedAt()
                : inFlight.getCreatedAt();
            if (since != null && since.isAfter(now.minus(ABANDONED_AFTER))) {
                throw ApiException.conflict("A sync of this account is already running. "
                    + "It will finish on its own; there is no need to start another.");
            }
            inFlight.setStatus("FAILED");
            inFlight.setErrorMsg("Abandoned: no progress for an hour");
            inFlight.setCompletedAt(now);
            syncJobRepository.saveAndFlush(inFlight);
        }

        SyncJob job;
        try {
            job = syncJobRepository.saveAndFlush(SyncJob.builder()
                .user(user)
                .platform(platform)
                .jobType(jobType)
                .status("QUEUED")
                .build());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Two requests in the same instant: the index let one of them in.
            throw ApiException.conflict("A sync of this account is already running.");
        }
        return new QueuedJob(job.getJobId(), account.getAccountId());
    }

    /**
     * Syncs one account on the calling thread, start to finish.
     *
     * <p>For the nightly batch, which runs on its own scheduler thread and has every account in
     * the deployment to get through. Handing those to the sync queue instead — eight workers,
     * a hundred waiting — refused everything past the first hundred and eight, so on a
     * deployment of two thousand accounts almost nobody was synced overnight. One after another
     * is slower (roughly an hour for two thousand, bounded by Codeforces' own rate limit
     * anyway) and complete, and it leaves the queue free for the people pressing Sync.
     */
    public void syncNow(Long userId, String platform, String jobType) {
        QueuedJob job = self.createJob(userId, platform, jobType);
        runSync(job.jobId(), job.accountId(), userId, platform, jobType);
    }

    @Async("syncTaskExecutor")
    public void runSyncAsync(Long jobId, Long accountId, Long userId, String platform, String jobType) {
        runSync(jobId, accountId, userId, platform, jobType);
    }

    /** The sync itself, on whichever thread calls it. Never throws; failures are recorded. */
    public void runSync(Long jobId, Long accountId, Long userId, String platform, String jobType) {
        SyncJob job = syncJobRepository.findById(jobId).orElse(null);
        if (job == null) return;

        try {
            job.setStatus("RUNNING");
            job.setStartedAt(Instant.now());
            syncJobRepository.save(job);

            platformAccountRepository.updateSyncStatus(accountId, "RUNNING");

            int synced = switch (platform) {
                case "CODEFORCES" -> syncCf(userId, accountId, jobType.equals("FULL"));
                default -> 0;
            };

            // Contest history rides along with every Codeforces sync. It had its own endpoint
            // and nothing called it, so "Recent contests" stayed empty for everyone. Run after
            // the submissions, which it reads to count problems solved per round; a failure
            // here does not fail the submission sync that already succeeded.
            if ("CODEFORCES".equals(platform)) {
                try {
                    contestSyncService.syncCfContests(userId);
                } catch (Exception e) {
                    log.warn("Contest history sync failed for userId={}: {}", userId, e.getMessage());
                }
            }

            // Ratings, coverage and the roadmap move as soon as new history lands, rather than
            // whenever somebody next opens their dashboard. A failure here leaves last night's
            // analytics in place; it does not fail a sync that already succeeded.
            try {
                analytics.ifAvailable(a -> a.triggerAnalyticsRefresh(userId));
            } catch (Exception e) {
                log.warn("Analytics refresh after sync failed for userId={}: {}", userId, e.getMessage());
            }

            job.setStatus("COMPLETED");
            job.setItemsSynced(synced);
            job.setProgressPct(100.0);
            job.setCompletedAt(Instant.now());
            syncJobRepository.save(job);

            platformAccountRepository.updateSyncStatus(accountId, "COMPLETED");
            metrics.syncFinished(platform, true);
            metrics.syncItemsStored(platform, synced);
            log.info("Sync completed for userId={} platform={} items={}", userId, platform, synced);

        } catch (Exception e) {
            log.error("Sync failed for userId={} platform={}: {}", userId, platform, e.getMessage(), e);
            job.setStatus("FAILED");
            job.setErrorMsg(e.getMessage());
            job.setCompletedAt(Instant.now());
            syncJobRepository.save(job);
            platformAccountRepository.updateSyncStatus(accountId, "FAILED");
            metrics.syncFinished(platform, false);
        }
    }

    private int syncCf(Long userId, Long accountId, boolean full) {
        PlatformAccount account = platformAccountRepository.findById(accountId).orElseThrow();
        String handle = account.getHandle();

        // Update rating
        var userInfo = cfClient.getUserInfo(handle);
        if (userInfo != null && userInfo.getResult() != null && !userInfo.getResult().isEmpty()) {
            CfModels.User cfUser = userInfo.getResult().get(0);
            account.setCurrentRating(cfUser.getRating());
            account.setMaxRating(cfUser.getMaxRating());
        }

        // Sync submissions
        int from = 1;
        int total = 0;
        List<CfSubmission> batch = new ArrayList<>();

        while (true) {
            var resp = cfClient.getSubmissions(handle, from, CF_BATCH);
            if (resp == null || resp.getResult() == null || resp.getResult().isEmpty()) break;

            Map<Long, CfSubmission> known = new HashMap<>();
            for (CfSubmission k : cfSubmissionRepository.findKnown(userId,
                    resp.getResult().stream().map(CfModels.Submission::getId).toList())) {
                known.put(k.getCfSubmissionId(), k);
            }
            int backfilled = 0;
            for (CfModels.Submission s : resp.getResult()) {
                CfSubmission stored = known.get(s.getId());
                if (stored == null) {
                    batch.add(normalizer.normalizeCf(userId, s));
                } else if (stored.getParticipantType() == null && s.getAuthor() != null
                        && s.getAuthor().getParticipantType() != null) {
                    backfilled += backfill(userId, s);
                }
            }

            int fresh = batch.size();
            cfSubmissionRepository.saveAll(batch);
            total += fresh;
            batch.clear();

            if (resp.getResult().size() < CF_BATCH) break;
            // Incremental stops at the first page with nothing new on it — not at the first
            // submission already stored. History can have gaps (a sync that died halfway, or
            // rows the old global index filed under another account), and stopping at the first
            // known row left everything behind a gap unsynced for good. A page that only filled
            // in fields older rows were stored without counts as work too, so the first sync
            // after an upgrade walks the whole history once.
            if (!full && fresh == 0 && backfilled == 0) break;
            from += CF_BATCH;
        }

        account.setLastSyncedAt(Instant.now());
        platformAccountRepository.save(account);
        return total;
    }

    /**
     * Fills in what a submission stored before they were kept is missing: how it was sent
     * (in the round or in practice) and how many tests it passed. Contest-based skill ratings
     * need the first; without it every old submission would read as practice.
     */
    private int backfill(Long userId, CfModels.Submission s) {
        var result = mongoTemplate.updateFirst(
            org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("userId").is(userId)
                    .and("cfSubmissionId").is(s.getId())),
            new org.springframework.data.mongodb.core.query.Update()
                .set("participantType", s.getAuthor().getParticipantType())
                .set("passedTestCount", s.getPassedTestCount()),
            CfSubmission.class);
        return (int) result.getModifiedCount();
    }

    /** Another user's job reads as not found, so job ids say nothing about other accounts. */
    public SyncJob getSyncStatus(Long userId, Long jobId) {
        return syncJobRepository.findByJobIdAndUserUserId(jobId, userId)
            .orElseThrow(() -> ApiException.notFound("Sync job not found"));
    }

    public List<PlatformAccount> getLinkedAccounts(Long userId) {
        return platformAccountRepository.findByUserUserId(userId);
    }

    private void validateHandle(String platform, String handle) {
        boolean valid = switch (platform) {
            case "CODEFORCES" -> cfClient.handleExists(handle);
            default -> throw ApiException.badRequest("Unknown platform: " + platform);
        };
        if (!valid) throw ApiException.badRequest("Handle '" + handle + "' not found on " + platform);
    }

    private PlatformDto.Summary mapToSummary(PlatformAccount pa) {
        return PlatformDto.Summary.builder()
            .accountId(pa.getAccountId())
            .platform(pa.getPlatform())
            .handle(pa.getHandle())
            .currentRating(pa.getCurrentRating())
            .maxRating(pa.getMaxRating())
            .lastSyncedAt(pa.getLastSyncedAt())
            .syncStatus(pa.getSyncStatus())
            .build();
    }
}

