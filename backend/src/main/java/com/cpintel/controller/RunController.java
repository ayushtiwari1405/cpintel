package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.runner.CodeRunnerService;
import com.cpintel.runner.RunDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/run")
@RequiredArgsConstructor
@Tag(name = "Runner", description = "Compile and run a solution against sample tests locally")
@SecurityRequirement(name = "bearerAuth")
public class RunController {

    private final CodeRunnerService runner;

    @GetMapping("/languages")
    @Operation(summary = "Languages this deployment can build and run")
    public ResponseEntity<ApiResponse<List<RunDto.RuntimeInfo>>> languages() {
        return ResponseEntity.ok(ApiResponse.ok(runner.languages()));
    }

    @GetMapping("/status")
    @Operation(summary = "Whether local running is on, and whether it is sandboxed")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status() {
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "enabled", runner.isEnabled(),
            "isolated", runner.isIsolated())));
    }

    @PostMapping
    @Operation(summary = "Compile once, then run against each supplied test")
    public ResponseEntity<ApiResponse<RunDto.RunResponse>> run(
        @Valid @RequestBody RunDto.RunRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(runner.run(request)));
    }
}
