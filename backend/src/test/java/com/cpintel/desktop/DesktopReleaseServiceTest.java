package com.cpintel.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DesktopReleaseServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private final DesktopReleaseService service = new DesktopReleaseService(json);

    private static String asset(String name) {
        return """
            {"name": "%s", "size": 1000, "digest": "sha256:abc123",
             "browser_download_url": "https://github.com/o/r/releases/download/v1.2.3/%s",
             "url": "https://api.github.com/repos/o/r/releases/assets/1"}
            """.formatted(name, name);
    }

    @Test
    @DisplayName("Installers are recognised by name and sorted by platform; nothing else is offered")
    void classifiesInstallers() throws Exception {
        String release = """
            {"tag_name": "v1.2.3", "published_at": "2026-10-10T00:00:00Z", "assets": [%s]}
            """.formatted(String.join(",",
                asset("CPIntel-Setup-1.2.3-x64.exe"),
                asset("CPIntel-1.2.3-arm64.dmg"),
                asset("CPIntel-1.2.3-x64.dmg"),
                asset("CPIntel-1.2.3-x64.AppImage"),
                asset("CPIntel-1.2.3-amd64.deb"),
                asset("CPIntel-Setup-1.2.3-x64.exe.blockmap"),
                asset("latest.yml"),
                asset("../../etc/passwd")));

        var parsed = service.parse(json.readTree(release));

        assertThat(parsed.version()).isEqualTo("1.2.3");
        assertThat(parsed.assets()).extracting(DesktopReleaseService.Asset::name).containsExactly(
            "CPIntel-Setup-1.2.3-x64.exe", "CPIntel-1.2.3-arm64.dmg", "CPIntel-1.2.3-x64.dmg",
            "CPIntel-1.2.3-x64.AppImage", "CPIntel-1.2.3-amd64.deb");
        assertThat(parsed.assets()).extracting(DesktopReleaseService.Asset::platform)
            .containsExactly("windows", "mac", "mac", "linux", "linux");
        assertThat(parsed.assets()).extracting(DesktopReleaseService.Asset::arch)
            .containsExactly("x64", "arm64", "x64", "x64", "x64");
        assertThat(parsed.assets().get(0).sha256()).isEqualTo("abc123");
    }

    @Test
    @DisplayName("Without a repository configured there is no release, and GitHub is not asked")
    void unconfigured() {
        assertThat(service.configured()).isFalse();
        assertThat(service.latest()).isEmpty();
    }

    @Test
    @DisplayName("A private repository's installer is fetched with the token, and the storage host never sees it")
    void privateDownloadDropsTokenOnRedirect() throws Exception {
        HttpServer github = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + github.getAddress().getPort();
        List<String> authSeen = new ArrayList<>();
        github.createContext("/repos/o/r/releases/latest", ex -> {
            authSeen.add("release:" + ex.getRequestHeaders().getFirst("Authorization"));
            byte[] body = ("{\"tag_name\": \"v2.0.0\", \"assets\": [{\"name\": "
                + "\"CPIntel-Setup-2.0.0-x64.exe\", \"size\": 5, \"digest\": \"sha256:ff\", "
                + "\"browser_download_url\": \"" + base + "/public\", "
                + "\"url\": \"" + base + "/api-asset\"}]}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        github.createContext("/api-asset", ex -> {
            authSeen.add("asset:" + ex.getRequestHeaders().getFirst("Authorization"));
            ex.getResponseHeaders().add("Location", base + "/storage");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        github.createContext("/storage", ex -> {
            authSeen.add("storage:" + ex.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        github.start();
        try {
            ReflectionTestUtils.setField(service, "repository", "o/r");
            ReflectionTestUtils.setField(service, "token", "secret");
            ReflectionTestUtils.setField(service, "api", base);

            var release = service.latest().orElseThrow();
            assertThat(release.version()).isEqualTo("2.0.0");
            assertThat(service.redirectable()).isFalse();

            var asset = service.asset("CPIntel-Setup-2.0.0-x64.exe").orElseThrow();
            assertThat(service.asset("anything-else.exe")).isEmpty();
            try (var in = service.open(asset).body()) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
            }
            assertThat(authSeen).containsExactly(
                "release:Bearer secret", "asset:Bearer secret", "storage:null");
        } finally {
            github.stop(0);
        }
    }

    @Test
    @DisplayName("A build made on this server is served before GitHub, and only files it lists")
    void localBuild(@TempDir Path desktop) throws IOException {
        Path current = Files.createDirectories(desktop.resolve("current"));
        for (String f : List.of("CPIntel-Setup-1.0.0-x64.exe", "CPIntel-1.0.0-arm64.zip",
                "CPIntel-1.0.0-x86_64.AppImage")) {
            Files.writeString(current.resolve(f), "x");
        }
        Files.writeString(current.resolve("release.json"), """
            {"version": "1.0.0", "builtAt": "2026-10-10T00:00:00Z",
             "serverUrl": "https://cpintel.example.edu", "source": "abc",
             "assets": [
               {"name": "CPIntel-Setup-1.0.0-x64.exe", "size": 1, "sha256": "aa"},
               {"name": "CPIntel-1.0.0-arm64.zip", "size": 1, "sha256": "bb"},
               {"name": "CPIntel-1.0.0-x64.zip", "size": 1, "sha256": "cc"},
               {"name": "../release.json", "size": 1, "sha256": "dd"}
             ]}
            """);
        ReflectionTestUtils.setField(service, "localDir", desktop.toString());
        // Pointing nowhere: a local build must be answered without asking GitHub.
        ReflectionTestUtils.setField(service, "repository", "o/r");
        ReflectionTestUtils.setField(service, "api", "http://127.0.0.1:1");

        var release = service.latest().orElseThrow();
        assertThat(release.version()).isEqualTo("1.0.0");
        // Listed but missing (the x64 zip) and unsafe names are dropped; unlisted files ignored.
        assertThat(release.assets()).extracting(DesktopReleaseService.Asset::name)
            .containsExactly("CPIntel-Setup-1.0.0-x64.exe", "CPIntel-1.0.0-arm64.zip");
        var mac = service.asset("CPIntel-1.0.0-arm64.zip").orElseThrow();
        assertThat(mac.platform()).isEqualTo("mac");
        assertThat(mac.format()).isEqualTo("zip");
        assertThat(mac.file()).isEqualTo(current.resolve("CPIntel-1.0.0-arm64.zip"));
        assertThat(service.asset("CPIntel-1.0.0-x86_64.AppImage")).isEmpty();
    }
}
