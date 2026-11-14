package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.exception.ApiException;
import com.cpintel.labs.LabCheckService;
import com.cpintel.labs.LabService;
import com.cpintel.labs.LabsDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/labs")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Labs", description = "Lab assignments set in your classrooms")
@SecurityRequirement(name = "bearerAuth")
public class LabController {

    /** One check per student per minute: each is a call to Codeforces and to LeetCode. */
    static final Duration CHECK_EVERY = Duration.ofMinutes(1);

    private final LabService labs;
    private final LabCheckService checker;
    private final RedisTemplate<String, Object> redis;

    @GetMapping("/mine")
    @Operation(summary = "Your open and past labs, with what you have solved")
    public ResponseEntity<ApiResponse<LabsDto.MyLabs>> mine(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(labs.mine(userId)));
    }

    @GetMapping("/problems/{labProblemId}/leetcode")
    @Operation(summary = "A LeetCode lab problem's statement and starter code, to solve it here")
    public ResponseEntity<ApiResponse<LabsDto.LeetCodeProblem>> leetCodeProblem(
        @AuthenticationPrincipal Long userId, @PathVariable Long labProblemId) {
        return ResponseEntity.ok(ApiResponse.ok(labs.leetCodeProblem(userId, labProblemId)));
    }

    @PostMapping("/check")
    @Operation(summary = "Look for your new solves now, rather than at the next scheduled check")
    public ResponseEntity<ApiResponse<LabsDto.CheckResult>> check(@AuthenticationPrincipal Long userId) {
        if (!claim(userId)) {
            throw ApiException.tooManyRequests("You checked less than a minute ago. Try again shortly.");
        }
        int recorded = checker.checkStudent(userId);
        return ResponseEntity.ok(ApiResponse.ok(new LabsDto.CheckResult(recorded, Instant.now())));
    }

    private boolean claim(Long userId) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue()
                .setIfAbsent("lab-check:user:" + userId, "1", CHECK_EVERY));
        } catch (Exception e) {
            // Without Redis the outbound limiter still paces the calls; let the check through.
            log.debug("Lab check throttle unavailable: {}", e.getMessage());
            return true;
        }
    }
}
