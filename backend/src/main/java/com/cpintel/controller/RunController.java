package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.common.Languages;
import com.cpintel.exception.ApiException;
import com.cpintel.groups.LanguagePolicy;
import com.cpintel.runner.CodeRunnerService;
import com.cpintel.runner.RunDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/run")
@RequiredArgsConstructor
@Tag(name = "Runner", description = "Compile and run a solution against sample tests locally")
@SecurityRequirement(name = "bearerAuth")
public class RunController {

    private final CodeRunnerService runner;
    private final LanguagePolicy languagePolicy;

    /**
     * Languages this deployment can build and run, narrowed to what the event allows.
     *
     * <p>The narrowing happens here rather than inside {@link CodeRunnerService} deliberately.
     * The runner is a rehearsal harness — it compiles a file and diffs the output — and it
     * knows nothing about examinations, rosters or administrators. Teaching it would put event
     * rules inside the one component that is genuinely just a compiler driver, for no gain:
     * the principal and the policy both already live out here.
     *
     * <p>With no contest named — Practice, or any caller that omits the parameters — every
     * runtime this host has is offered, which is what Practice has always been.
     */
    @GetMapping("/languages")
    @Operation(summary = "Languages this deployment can build and run")
    public ResponseEntity<ApiResponse<List<RunDto.RuntimeInfo>>> languages(
        @AuthenticationPrincipal Long userId,
        @RequestParam(required = false) String platform,
        @RequestParam(required = false) String contestId) {

        Set<String> allowed = restriction(userId, platform, contestId);
        List<RunDto.RuntimeInfo> all = runner.languages();
        if (allowed.isEmpty()) return ResponseEntity.ok(ApiResponse.ok(all));

        // The runner's ids are the catalogue's ids — one namespace on purpose, so this is a
        // set membership test rather than a second mapping that could disagree with the one
        // the Submit button uses.
        return ResponseEntity.ok(ApiResponse.ok(
            all.stream().filter(r -> allowed.contains(r.id())).toList()));
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
        @AuthenticationPrincipal Long userId,
        @Valid @RequestBody RunDto.RunRequest request) {

        Set<String> allowed =
            restriction(userId, request.platform(), request.contestId());
        if (!allowed.isEmpty() && !allowed.contains(request.language())) {
            // Refused rather than silently run. A candidate who has somehow selected a language
            // the paper does not take needs to be told now, while they can still rewrite it,
            // rather than at the submit button with the clock further along.
            String names = allowed.stream().map(Languages::labelFor).sorted()
                .reduce((a, b) -> a + ", " + b).orElse("");
            throw ApiException.badRequest(
                "This examination only accepts " + names + ", so " 
                + Languages.labelFor(request.language()) + " cannot be run here either.");
        }

        return ResponseEntity.ok(ApiResponse.ok(runner.run(request)));
    }

    /**
     * What the named event restricts languages to, or nothing at all.
     *
     * <p>Tolerant of a half-supplied pair: naming an event is only ever a way to be held to
     * more rules than the default, so a caller that sends one half of it gets the unrestricted
     * runner rather than an error about a parameter they did not know they had to pair.
     */
    private Set<String> restriction(Long userId, String platform, String contestId) {
        if (userId == null || platform == null || platform.isBlank()
            || contestId == null || contestId.isBlank()) {
            return Set.of();
        }
        return languagePolicy.restrictionFor(userId, platform, contestId);
    }
}
