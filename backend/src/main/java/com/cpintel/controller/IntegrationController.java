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
    private final com.cpintel.service.HandleVerificationService verification;
    private final com.cpintel.service.CsesAccountService csesAccounts;
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

    @PostMapping("/leetcode/link")
    @Operation(summary = "Link a LeetCode handle, for lab assignments")
    public ResponseEntity<ApiResponse<PlatformDto.Summary>> linkLeetCode(
        @AuthenticationPrincipal Long userId,
        @Valid @RequestBody PlatformDto.LinkRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
            syncService.linkAccount(userId, "LEETCODE", req.getHandle())));
    }

    @DeleteMapping("/leetcode")
    public ResponseEntity<ApiResponse<Void>> unlinkLeetCode(@AuthenticationPrincipal Long userId) {
        syncService.unlinkAccount(userId, "LEETCODE");
        return ResponseEntity.ok(ApiResponse.message("LeetCode account unlinked"));
    }

    @PostMapping("/cses/connect")
    @Operation(summary = "Start connecting a CSES account: returns the cses.fi address to sign in at")
    public ResponseEntity<ApiResponse<com.cpintel.service.CsesAccountService.Started>> csesStart(
        @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(csesAccounts.start(userId)));
    }

    @PostMapping("/cses/connect/check")
    @Operation(summary = "Whether the CSES sign-in has been approved; connects the account when it has")
    public ResponseEntity<ApiResponse<com.cpintel.service.CsesAccountService.Checked>> csesCheck(
        @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(csesAccounts.check(userId)));
    }

    @DeleteMapping("/cses")
    public ResponseEntity<ApiResponse<Void>> csesDisconnect(@AuthenticationPrincipal Long userId) {
        csesAccounts.disconnect(userId);
        return ResponseEntity.ok(ApiResponse.message("CSES account disconnected"));
    }

    @PostMapping("/{platform}/verify")
    @Operation(summary = "Start proving a linked handle is yours")
    public ResponseEntity<ApiResponse<com.cpintel.service.HandleVerificationService.Challenge>> startVerify(
        @AuthenticationPrincipal Long userId, @PathVariable String platform) {
        return ResponseEntity.ok(ApiResponse.ok(verification.start(userId, platformOf(platform))));
    }

    @PostMapping("/{platform}/verify/check")
    @Operation(summary = "Look for the proof on the platform, and verify the handle if it is there")
    public ResponseEntity<ApiResponse<PlatformDto.Summary>> checkVerify(
        @AuthenticationPrincipal Long userId, @PathVariable String platform) {
        return ResponseEntity.ok(ApiResponse.ok(verification.check(userId, platformOf(platform))));
    }

    private static String platformOf(String path) {
        return switch (path) {
            case "codeforces" -> "CODEFORCES";
            case "leetcode" -> "LEETCODE";
            default -> throw com.cpintel.exception.ApiException.notFound("No platform " + path);
        };
    }

    @GetMapping("/sync-status/{jobId}")
    public ResponseEntity<ApiResponse<SyncJobDto>> getSyncStatus(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long jobId) {
        SyncJob job = syncService.getSyncStatus(userId, jobId);
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
