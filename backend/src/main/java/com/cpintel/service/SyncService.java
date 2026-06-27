package com.cpintel.service;

import com.cpintel.dto.PlatformDto;
import com.cpintel.entity.PlatformAccount;
import com.cpintel.entity.SyncJob;
import com.cpintel.entity.User;
import com.cpintel.entity.mongo.CfSubmission;
import com.cpintel.entity.mongo.LcSubmission;
import com.cpintel.exception.ApiException;
import com.cpintel.integration.PlatformNormalizer;
import com.cpintel.integration.codeforces.CfModels;
import com.cpintel.integration.codeforces.CodeforcesClient;
import com.cpintel.integration.codechef.CodeChefClient;
import com.cpintel.integration.leetcode.LeetCodeClient;
import com.cpintel.integration.leetcode.LcModels;
import com.cpintel.repository.jpa.*;
import com.cpintel.repository.mongo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SyncService {

    private final UserRepository userRepository;
    private final PlatformAccountRepository platformAccountRepository;
    private final SyncJobRepository syncJobRepository;
    private final CfSubmissionRepository cfSubmissionRepository;
    private final LcSubmissionRepository lcSubmissionRepository;
    private final CodeforcesClient cfClient;
    private final LeetCodeClient lcClient;
    private final CodeChefClient ccClient;
    private final PlatformNormalizer normalizer;

    private static final int CF_BATCH = 200;
    private static final int LC_RECENT = 100;

    @Transactional
    public PlatformDto.Summary linkAccount(Long userId, String platform, String handle) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> ApiException.notFound("User not found"));

        if (platformAccountRepository.existsByUserUserIdAndPlatform(userId, platform))
            throw ApiException.conflict(platform + " account already linked");

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
        platformAccountRepository.delete(account);
    }

    @Transactional
    public PlatformDto.SyncResponse triggerSync(Long userId, String platform, String jobType) {
        PlatformAccount account = platformAccountRepository
            .findByUserUserIdAndPlatform(userId, platform)
            .orElseThrow(() -> ApiException.notFound(platform + " account not linked"));

        User user = userRepository.findById(userId)
            .orElseThrow(() -> ApiException.notFound("User not found"));

        SyncJob job = SyncJob.builder()
            .user(user)
            .platform(platform)
            .jobType(jobType)
            .status("QUEUED")
            .build();
        job = syncJobRepository.save(job);

        Long jobId = job.getJobId();
        String finalJobType = jobType;

        // Run async
        runSyncAsync(jobId, account.getAccountId(), userId, platform, finalJobType);

        return PlatformDto.SyncResponse.builder()
            .jobId(jobId)
            .platform(platform)
            .status("QUEUED")
            .message("Sync started. Check status with /api/integrations/" + platform.toLowerCase() + "/status")
            .build();
    }

    @Async("syncTaskExecutor")
    public void runSyncAsync(Long jobId, Long accountId, Long userId, String platform, String jobType) {
        SyncJob job = syncJobRepository.findById(jobId).orElse(null);
        if (job == null) return;

        try {
            job.setStatus("RUNNING");
            job.setStartedAt(Instant.now());
            syncJobRepository.save(job);

            platformAccountRepository.updateSyncStatus(accountId, "RUNNING");

            int synced = switch (platform) {
                case "CODEFORCES" -> syncCf(userId, accountId, jobType.equals("FULL"));
                case "LEETCODE"   -> syncLc(userId, accountId);
                case "CODECHEF"   -> syncCc(userId, accountId);
                default -> 0;
            };

            job.setStatus("COMPLETED");
            job.setItemsSynced(synced);
            job.setProgressPct(100.0);
            job.setCompletedAt(Instant.now());
            syncJobRepository.save(job);

            platformAccountRepository.updateSyncStatus(accountId, "COMPLETED");
            log.info("Sync completed for userId={} platform={} items={}", userId, platform, synced);

        } catch (Exception e) {
            log.error("Sync failed for userId={} platform={}: {}", userId, platform, e.getMessage(), e);
            job.setStatus("FAILED");
            job.setErrorMsg(e.getMessage());
            job.setCompletedAt(Instant.now());
            syncJobRepository.save(job);
            platformAccountRepository.updateSyncStatus(accountId, "FAILED");
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

            for (CfModels.Submission s : resp.getResult()) {
                if (!cfSubmissionRepository.existsByCfSubmissionId(s.getId())) {
                    batch.add(normalizer.normalizeCf(userId, s));
                } else if (!full) {
                    // In incremental mode, stop when we hit already-seen submissions
                    break;
                }
            }

            cfSubmissionRepository.saveAll(batch);
            total += batch.size();
            batch.clear();

            if (resp.getResult().size() < CF_BATCH) break;
            if (!full && total > 0) break;
            from += CF_BATCH;
        }

        account.setLastSyncedAt(Instant.now());
        platformAccountRepository.save(account);
        return total;
    }

    private int syncLc(Long userId, Long accountId) {
        PlatformAccount account = platformAccountRepository.findById(accountId).orElseThrow();
        String handle = account.getHandle();

        // Update rating
        var contestRanking = lcClient.getContestRanking(handle);
        if (contestRanking != null && contestRanking.getRating() != null) {
            account.setCurrentRating(contestRanking.getRating().intValue());
        }

        // Sync recent accepted submissions
        var subs = lcClient.getRecentSubmissions(handle, LC_RECENT);
        int total = 0;

        if (subs != null && subs.getRecentAcSubmissionList() != null) {
            List<LcSubmission> toSave = new ArrayList<>();
            for (var s : subs.getRecentAcSubmissionList()) {
                Long id = s.getId() != null ? Long.parseLong(s.getId()) : null;
                if (id != null && !lcSubmissionRepository.existsByLcSubmissionId(id)) {
                    toSave.add(normalizer.normalizeLc(userId, s));
                }
            }
            lcSubmissionRepository.saveAll(toSave);
            total = toSave.size();
        }

        account.setLastSyncedAt(Instant.now());
        platformAccountRepository.save(account);
        return total;
    }

    private int syncCc(Long userId, Long accountId) {
        PlatformAccount account = platformAccountRepository.findById(accountId).orElseThrow();
        String handle = account.getHandle();

        var profile = ccClient.getUserProfile(handle);
        if (profile != null) {
            account.setCurrentRating(profile.getCurrentRating());
            account.setMaxRating(profile.getHighestRating());
        }

        account.setLastSyncedAt(Instant.now());
        platformAccountRepository.save(account);
        return 0; // Full submission sync for CC deferred to analytics phase
    }

    public SyncJob getSyncStatus(Long jobId) {
        return syncJobRepository.findById(jobId)
            .orElseThrow(() -> ApiException.notFound("Sync job not found"));
    }

    public List<PlatformAccount> getLinkedAccounts(Long userId) {
        return platformAccountRepository.findByUserUserId(userId);
    }

    private void validateHandle(String platform, String handle) {
        boolean valid = switch (platform) {
            case "CODEFORCES" -> cfClient.handleExists(handle);
            case "LEETCODE"   -> lcClient.handleExists(handle);
            case "CODECHEF"   -> ccClient.handleExists(handle);
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

