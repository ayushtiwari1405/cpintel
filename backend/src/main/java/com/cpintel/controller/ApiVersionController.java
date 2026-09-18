package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * What this server speaks, for clients that ship separately from it.
 *
 * <p>The browser SPA is served from the same origin and can never disagree with the API. The
 * desktop app can: it is packaged, installed, and then sits on someone's machine while the
 * server moves on. Without something like this, a client one version behind discovers the
 * mismatch as a 404 on a route it expected, which looks like a broken server rather than an
 * out-of-date install.
 *
 * <p>Unauthenticated on purpose — a client needs to know whether it can talk to this deployment
 * before it has credentials, and there is nothing here worth protecting.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Meta", description = "API version handshake")
public class ApiVersionController {

    /** Bumped when a change breaks clients built against the previous one. */
    private static final String CURRENT = "v1";

    /** The oldest version this server still answers. Equal to CURRENT until v2 exists. */
    private static final String MINIMUM = "v1";

    @Value("${cpintel.desktop.minimum-version:0.0.0}")
    private String minimumDesktopVersion;

    @GetMapping("/version")
    @Operation(summary = "Which API versions this server speaks")
    public ResponseEntity<ApiResponse<Map<String, String>>> version() {
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "current", CURRENT,
            "minimum", MINIMUM,
            "minimumDesktopVersion", minimumDesktopVersion)));
    }
}
