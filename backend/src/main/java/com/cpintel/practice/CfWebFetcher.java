package com.cpintel.practice;

import com.cpintel.exception.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Outbound HTTP for the Codeforces <em>website</em>, performed by curl rather than from the JVM.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Codeforces serves its HTML from behind Cloudflare, and Cloudflare fingerprints the TLS
 * handshake. Java's TLS ClientHello is distinctive, and it is refused: every request from
 * {@code java.net.http.HttpClient} comes back 403 with the "Just a moment..." interstitial. This
 * was measured rather than guessed, with the same cookies and the same User-Agent throughout:
 *
 * <pre>
 *   curl              200      python urllib     200      java.net.http     403
 *   ...and from Java, 403 on HTTP/1.1 and HTTP/2, with minimal headers and with a full
 *   browser header set alike. The JSON API, which is not behind the challenge, returns 200
 *   from Java — so connectivity, DNS and the cookies were never the problem.
 * </pre>
 *
 * <p>No combination of headers fixes it, because headers are not what is being judged. The only
 * remedies are to stop using the JVM's TLS stack for these calls or to stop making them. This
 * class takes the first: curl is already installed in the backend image (the container
 * healthcheck uses it), so it costs no new dependency.
 *
 * <p>The JSON API is untouched by any of this and still goes through the ordinary WebClient —
 * it is not challenged, and it is where ratings, submissions and the problemset come from.
 *
 * <h2>Keeping the session out of the process table</h2>
 *
 * <p>Cookies are a live credential for the user's Codeforces account, and anything on a command
 * line is world-readable from {@code /proc} for as long as the process runs. Nothing sensitive
 * is ever passed as an argument: the URL, headers and cookie all go to curl through a config
 * file fed on stdin, and request bodies go through a private temporary file that is deleted
 * afterwards.
 */
@Component
@Slf4j
public class CfWebFetcher {

    @Value("${cpintel.practice.curl-path:curl}")
    private String curlPath;

    @Value("${cpintel.practice.curl-timeout-seconds:30}")
    private int timeoutSeconds;

    /** How long to wait for the process itself before giving up on it. */
    private static final long PROCESS_GRACE_SECONDS = 15;

    /** A response body, plus the status line curl reported. */
    public record Response(int status, String body) {
        public boolean ok() { return status >= 200 && status < 400; }
    }

    /** GET a page with the user's session. */
    public Response get(String url, String cookieHeader, String userAgent,
                        Map<String, String> extraHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        headers.put("Accept-Language", "en-US,en;q=0.9");
        if (extraHeaders != null) headers.putAll(extraHeaders);
        return run(url, cookieHeader, userAgent, headers, null, null);
    }

    /**
     * POST a body that has already been serialised — a multipart envelope or a urlencoded form.
     *
     * <p>The body is built by the caller, exactly as it was when the JVM sent it, so nothing
     * about the request shape changes here; only which process opens the socket.
     */
    public Response post(String url, String cookieHeader, String userAgent,
                         String contentType, byte[] body, Map<String, String> extraHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        headers.put("Accept-Language", "en-US,en;q=0.9");
        headers.put("Content-Type", contentType);
        if (extraHeaders != null) headers.putAll(extraHeaders);
        return run(url, cookieHeader, userAgent, headers, body, contentType);
    }

    // -- internals ---------------------------------------------------------

    private Response run(String url, String cookieHeader, String userAgent,
                         Map<String, String> headers, byte[] body, String contentType) {
        Path bodyFile = null;
        try {
            List<String> config = new ArrayList<>();
            config.add("url = " + quote(url));
            config.add("user-agent = " + quote(userAgent));
            if (cookieHeader != null && !cookieHeader.isBlank()) {
                config.add("header = " + quote("Cookie: " + cookieHeader));
            }
            headers.forEach((k, v) -> config.add("header = " + quote(k + ": " + v)));
            config.add("silent");
            config.add("show-error");
            config.add("location");            // follow redirects, as the JVM client did
            config.add("compressed");
            config.add("max-time = " + timeoutSeconds);
            // Status code on its own line after the body, so one read gets both.
            //
            // Written literally rather than through quote(): curl's config parser interprets
            // the two-character sequence backslash-n inside a quoted value, so what has to
            // reach the file is a backslash and an 'n' — not the newline character itself,
            // which quote() would escape into a broken line.
            config.add("write-out = \"\\n%{http_code}\"");

            if (body != null) {
                bodyFile = Files.createTempFile("cpintel-cf-", ".body");
                restrictToOwner(bodyFile);
                Files.write(bodyFile, body);
                config.add("data-binary = " + quote("@" + bodyFile.toAbsolutePath()));
                config.add("request = POST");
            }

            ProcessBuilder pb = new ProcessBuilder(curlPath, "--config", "-");
            pb.redirectErrorStream(false);
            Process proc = pb.start();

            try (OutputStream stdin = proc.getOutputStream()) {
                stdin.write(String.join("\n", config).getBytes(StandardCharsets.UTF_8));
            }

            byte[] out = proc.getInputStream().readAllBytes();
            byte[] err = proc.getErrorStream().readAllBytes();

            if (!proc.waitFor(timeoutSeconds + PROCESS_GRACE_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new ApiException(HttpStatus.BAD_GATEWAY, "CF_UNREACHABLE",
                    "Codeforces did not answer in time.");
            }
            if (proc.exitValue() != 0) {
                String message = new String(err, StandardCharsets.UTF_8).trim();
                throw new ApiException(HttpStatus.BAD_GATEWAY, "CF_UNREACHABLE",
                    "Could not reach Codeforces: "
                        + (message.isEmpty() ? "curl exited " + proc.exitValue() : message));
            }

            return split(new String(out, StandardCharsets.UTF_8));

        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "CF_UNREACHABLE",
                "Could not reach Codeforces: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "CF_UNREACHABLE",
                "Interrupted while reading from Codeforces.");
        } finally {
            if (bodyFile != null) {
                try {
                    Files.deleteIfExists(bodyFile);
                } catch (IOException e) {
                    log.warn("Could not remove temporary request body {}: {}",
                        bodyFile, e.getMessage());
                }
            }
        }
    }

    /** The status curl appended, and everything before it. */
    private Response split(String raw) {
        int cut = raw.lastIndexOf('\n');
        if (cut < 0) return new Response(0, raw);
        String tail = raw.substring(cut + 1).trim();
        try {
            return new Response(Integer.parseInt(tail), raw.substring(0, cut));
        } catch (NumberFormatException e) {
            // No trailing status means the body itself ended without one; return it as-is
            // rather than throwing away a page over a parse detail.
            return new Response(0, raw);
        }
    }

    /**
     * Owner-only permissions on the request body.
     *
     * <p>Best-effort: a filesystem without POSIX permissions simply skips it, and the file is
     * short-lived and deleted regardless.
     */
    private void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path,
                java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                 java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException e) {
            log.debug("Could not restrict permissions on {}: {}", path, e.getMessage());
        }
    }

    /** curl config values are double-quoted, with backslash escaping inside. */
    private static String quote(String value) {
        if (value == null) return "\"\"";
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
