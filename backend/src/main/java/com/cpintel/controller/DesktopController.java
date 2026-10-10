package com.cpintel.controller;

import com.cpintel.common.ApiResponse;
import com.cpintel.desktop.DesktopReleaseService;
import com.cpintel.desktop.DesktopReleaseService.Asset;
import com.cpintel.exception.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the desktop app is downloaded from, and how an installed one learns it is out of date.
 *
 * <p>Unauthenticated, like {@code /api/version}: the update check runs on the sign-in screen of
 * an installed app, and a download link is nothing to protect. Only installers listed in the
 * current release — this server's own build, or the configured repository's latest — are ever
 * served: the name in the path is looked up there, never used to build a URL or a path.
 */
@RestController
@RequestMapping("/api/v1/desktop")
@RequiredArgsConstructor
@Tag(name = "Desktop", description = "Desktop app downloads and updates")
public class DesktopController {

    private final DesktopReleaseService releases;

    @Value("${cpintel.desktop.minimum-version:0.0.0}")
    private String minimumVersion;

    @GetMapping("/release")
    @Operation(summary = "The newest desktop release and its installers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> release() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configured", releases.configured());
        body.put("minimumVersion", minimumVersion);
        releases.latest().ifPresentOrElse(r -> {
            body.put("version", r.version());
            body.put("publishedAt", r.publishedAt());
            body.put("assets", r.assets().stream().map(DesktopController::describe).toList());
        }, () -> {
            body.put("version", null);
            body.put("publishedAt", null);
            body.put("assets", List.of());
        });
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    private static Map<String, Object> describe(Asset a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", a.name());
        m.put("platform", a.platform());
        m.put("arch", a.arch());
        m.put("format", a.format());
        m.put("size", a.size());
        m.put("sha256", a.sha256());
        // Always through this server, so the page and the app never need GitHub's address.
        m.put("url", "/api/v1/desktop/download/" + a.name());
        return m;
    }

    @GetMapping("/download/{name:.+}")
    @Operation(summary = "Download one installer from the newest release")
    public ResponseEntity<?> download(@PathVariable String name) throws Exception {
        Asset asset = releases.asset(name)
            .orElseThrow(() -> ApiException.notFound("There is no installer called " + name
                + " in the current desktop release."));

        if (asset.file() != null) {
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(asset.size())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + asset.name() + "\"")
                .body(new FileSystemResource(asset.file()));
        }

        if (releases.redirectable()) {
            return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(asset.browserUrl())).build();
        }

        // A private repository: GitHub only hands the file over with the token, so it is
        // streamed through here rather than buffered.
        HttpResponse<InputStream> upstream = releases.open(asset);
        if (upstream.statusCode() != 200) {
            upstream.body().close();
            throw ApiException.notFound("GitHub would not hand over " + name + " right now.");
        }
        StreamingResponseBody body = out -> {
            try (InputStream in = upstream.body()) {
                in.transferTo(out);
            }
        };
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(asset.size())
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + asset.name() + "\"")
            .body(body);
    }
}
