package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.dto.PlatformDto;
import com.cpintel.entity.SyncJob;
import com.cpintel.mapper.UserMapper;
import com.cpintel.service.ContestSyncService;
import com.cpintel.service.SyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/integrations")
@RequiredArgsConstructor
@Tag(name = "Integrations", description = "Platform account linking and sync")
@SecurityRequirement(name = "bearerAuth")
public class IntegrationController {

    private final SyncService syncService;
    private final ContestSyncService contestSyncService;
    private final UserMapper userMapper;

    @GetMapping
    public ResponseEntity<ApiResponse<List<PlatformDto.Summary>>> getLinkedAccounts(
        @AuthenticationPrincipal Long userId) {
        List<PlatformDto.Summary> accounts = syncService.getLinkedAccounts(userId)
            .stream().map(userMapper::toPlatformSummary).collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.ok(accounts));
    }

    @PostMapping("/codeforces/link")
    public ResponseEntity<ApiResponse<PlatformDto.Summary>> linkCf(
        @AuthenticationPrincipal Long userId,
        @Valid @RequestBody PlatformDto.LinkRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
            syncService.linkAccount(userId, "CODEFORCES", req.getHandle())));
    }

    @PostMapping("/codeforces/sync")
    public ResponseEntity<ApiResponse<PlatformDto.SyncResponse>> syncCf(
        @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(
            syncService.triggerSync(userId, "CODEFORCES", "INCREMENTAL")));
    }

    @PostMapping("/contests/sync")
    @Operation(summary = "Sync contest history from Codeforces")
    public ResponseEntity<ApiResponse<ContestSyncResult>> syncContests(
        @AuthenticationPrincipal Long userId) {
        int cfContests = contestSyncService.syncCfContests(userId);
        return ResponseEntity.ok(ApiResponse.ok(
            new ContestSyncResult(cfContests, 0, 0,
                "Synced " + cfContests + " new contests")));
    }

    @DeleteMapping("/codeforces")
    public ResponseEntity<ApiResponse<Void>> unlinkCf(@AuthenticationPrincipal Long userId) {
        syncService.unlinkAccount(userId, "CODEFORCES");
        return ResponseEntity.ok(ApiResponse.message("Codeforces account unlinked"));
    }

    @GetMapping("/sync-status/{jobId}")
    public ResponseEntity<ApiResponse<SyncJobDto>> getSyncStatus(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long jobId) {
        SyncJob job = syncService.getSyncStatus(jobId);
        return ResponseEntity.ok(ApiResponse.ok(toSyncJobDto(job)));
    }

    private SyncJobDto toSyncJobDto(SyncJob job) {
        return new SyncJobDto(job.getJobId(), job.getPlatform(), job.getStatus(),
            job.getProgressPct(), job.getItemsSynced(),
            job.getErrorMsg(), job.getStartedAt(), job.getCompletedAt());
    }

    record SyncJobDto(Long jobId, String platform, String status,
        Double progressPct, Integer itemsSynced, String errorMsg,
        Instant startedAt, Instant completedAt) {}

    record ContestSyncResult(int cfContests, int lcContests, int ccContests, String message) {}
}
