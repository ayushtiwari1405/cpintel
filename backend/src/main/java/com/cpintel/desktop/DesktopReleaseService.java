package com.cpintel.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The newest desktop app, as published on the GitHub repository this deployment builds from.
 *
 * <p>Installers are built by the repository's own Actions workflow on a version tag and attached
 * to a GitHub Release (.github/workflows/electron.yml). Whoever runs a deployment builds from
 * their own copy of the repository, so which repository to read is configuration — nothing here
 * names one. The download page and the desktop app's update ribbon both ask this server rather
 * than GitHub, so neither needs to know where the files live, and a private repository works too:
 * with a token configured, downloads are streamed through this server instead of redirected.
 *
 * <p>The answer is cached for ten minutes. Unauthenticated, GitHub allows sixty API calls an hour
 * per address, and every page load of every student asks; on a failed refresh the last good
 * answer is kept, because a release that existed a minute ago has not stopped existing.
 */
@Service
@Slf4j
public class DesktopReleaseService {

    private static final Duration CACHE_FOR = Duration.ofMinutes(10);

    /**
     * Installer names, as electron/package.json's artifactName settings write them:
     * CPIntel-Setup-1.2.3-x64.exe, CPIntel-1.2.3-arm64.dmg, CPIntel-1.2.3-x64.AppImage, ...
     */
    private static final Pattern INSTALLER = Pattern.compile(
        "^CPIntel-(?:Setup-)?[0-9][0-9A-Za-z.+-]*-(x64|arm64|amd64|x86_64|aarch64)\\.(exe|dmg|AppImage|deb)$");

    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        // Redirects are followed by hand: GitHub hands asset downloads to a storage host, which
        // must not receive this server's token.
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    /** owner/name of the repository whose Releases carry the installers. Blank: none. */
    @Value("${cpintel.desktop.release-repository:}")
    private String repository;

    /** Only for a private repository. Read access to its contents is all it needs. */
    @Value("${cpintel.desktop.github-token:}")
    private String token;

    @Value("${cpintel.desktop.github-api:https://api.github.com}")
    private String api;

    private volatile Release cached;
    private volatile Instant cachedAt = Instant.EPOCH;

    public DesktopReleaseService(ObjectMapper json) {
        this.json = json;
    }

    public boolean configured() {
        return repository != null && repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    }

    /** The newest published release, or empty when there is none or GitHub cannot be reached. */
    public Optional<Release> latest() {
        if (!configured()) return Optional.empty();
        if (cached != null && Instant.now().isBefore(cachedAt.plus(CACHE_FOR))) {
            return Optional.of(cached);
        }
        synchronized (this) {
            if (cached != null && Instant.now().isBefore(cachedAt.plus(CACHE_FOR))) {
                return Optional.of(cached);
            }
            try {
                HttpResponse<String> res = http.send(
                    github(URI.create(api + "/repos/" + repository + "/releases/latest"))
                        .header("Accept", "application/vnd.github+json").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200) {
                    cached = parse(json.readTree(res.body()));
                    cachedAt = Instant.now();
                } else {
                    // 404 is the ordinary answer before the first release is published.
                    log.debug("GitHub answered {} for the latest release of {}", res.statusCode(),
                        repository);
                    cachedAt = Instant.now();
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("Could not read the latest desktop release of {}: {}", repository,
                    e.getMessage());
                // Try again on the next request rather than in ten minutes.
            }
            return Optional.ofNullable(cached);
        }
    }

    Release parse(JsonNode release) {
        String tag = release.path("tag_name").asText("");
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        List<Asset> assets = new ArrayList<>();
        for (JsonNode a : release.path("assets")) {
            String name = a.path("name").asText("");
            Matcher m = INSTALLER.matcher(name);
            if (!m.matches()) continue;
            String digest = a.path("digest").asText("");
            assets.add(new Asset(
                name,
                platformOf(m.group(2)),
                archOf(m.group(1)),
                m.group(2),
                a.path("size").asLong(),
                digest.startsWith("sha256:") ? digest.substring(7) : null,
                a.path("browser_download_url").asText(),
                a.path("url").asText()));
        }
        return new Release(version, release.path("published_at").asText(null),
            release.path("html_url").asText(null), assets);
    }

    private static String platformOf(String extension) {
        return switch (extension) {
            case "exe" -> "windows";
            case "dmg" -> "mac";
            default -> "linux";
        };
    }

    private static String archOf(String arch) {
        return switch (arch) {
            case "arm64", "aarch64" -> "arm64";
            default -> "x64";
        };
    }

    /** The installer of that name in the latest release; nothing else is ever served. */
    public Optional<Asset> asset(String name) {
        return latest().flatMap(r -> r.assets().stream().filter(a -> a.name().equals(name))
            .findFirst());
    }

    /** True when the asset can be fetched by anyone, so a redirect to GitHub serves it. */
    public boolean redirectable() {
        return token == null || token.isBlank();
    }

    /** Opens a private repository's asset for streaming through this server. */
    public HttpResponse<InputStream> open(Asset asset) throws Exception {
        HttpResponse<InputStream> res = http.send(
            github(URI.create(asset.apiUrl())).header("Accept", "application/octet-stream")
                .GET().build(),
            HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() / 100 == 3) {
            res.body().close();
            String location = res.headers().firstValue("Location")
                .orElseThrow(() -> new IllegalStateException("GitHub redirected nowhere"));
            // A pre-signed storage URL: the token must not follow it there.
            return http.send(HttpRequest.newBuilder(URI.create(location)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        }
        return res;
    }

    private HttpRequest.Builder github(URI uri) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "CPIntel");
        if (token != null && !token.isBlank()) b.header("Authorization", "Bearer " + token);
        return b;
    }

    public record Release(String version, String publishedAt, String pageUrl, List<Asset> assets) {}

    public record Asset(
        String name,
        /** windows, mac or linux. */
        String platform,
        /** x64 or arm64. */
        String arch,
        /** exe, dmg, AppImage or deb. */
        String format,
        long size,
        /** Hex SHA-256 as GitHub computed it on upload; null for assets older than that. */
        String sha256,
        String browserUrl,
        String apiUrl) {}
}
