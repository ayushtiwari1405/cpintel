package com.cpintel.integration.domjudge;

import com.cpintel.practice.PracticeDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Sample test cases for a DOMjudge problem, so the arena can seed the testcase console the way
 * the Codeforces side does.
 *
 * <p>The awkward part is that DOMjudge has no single stable answer. Sample data lives in the
 * problem package as {@code data/sample/*.in} and {@code *.ans}, and every version has exposed
 * it somewhere, but <em>where</em> moved between releases and between the API and the web UI.
 * Guessing one route and shipping it would mean the console silently comes up empty on any
 * build that names it differently — and the failure would land during a contest, when nobody
 * has time to debug it.
 *
 * <p>So this tries the known routes in order, uses whichever answers, and then remembers it.
 * The first problem opened pays for the probing; every problem after it goes straight to the
 * route that worked. The winning route is logged at INFO the first time it resolves, and
 * {@link #discoveredRoute()} reports it — between them, the first thing to check when samples
 * do not appear. {@code scripts/domjudge-probe.sh} answers the same question without starting
 * the application at all.
 *
 * <p>Only test cases flagged {@code sample} are ever read. The secret data a contest is judged
 * on is deliberately never requested, even though the admin credentials this runs under would
 * be allowed to fetch it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DomjudgeSampleClient {

    /** Samples do not change during a contest, so the first successful read is the last. */
    private static final Duration TTL = Duration.ofHours(6);

    /** An empty answer is held only briefly — it may be a permission, not an absence. */
    private static final Duration EMPTY_TTL = Duration.ofSeconds(60);

    private final DomjudgeClient domjudge;

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    /** Which route answered last, kept so later problems skip the probing. */
    private final AtomicReference<Route> known = new AtomicReference<>(null);

    private record Entry(List<PracticeDto.Sample> samples, Instant at) {}

    /**
     * The routes worth trying, cheapest and most-official first.
     *
     * The API routes are preferred over the web-UI ones because they return structured data
     * and are stable across themes; the zip routes are the fallback for builds that never
     * exposed test cases through the API at all.
     */
    private enum Route {
        API_CONTEST_TESTCASES,
        API_PROBLEM_TESTCASES,
        WEB_TEAM_SAMPLES_ZIP,
        WEB_PUBLIC_SAMPLES_ZIP
    }

    /** The route this instance answered on, or null if nothing has been fetched yet. */
    public String discoveredRoute() {
        Route route = known.get();
        return route == null ? null : route.name();
    }

    /**
     * Samples for one problem, newest-cached-first, never throwing.
     *
     * <p>An empty list is a legitimate answer — a problem can genuinely have no sample flagged
     * — and is treated much the same as "could not read them". The arena shows an empty console
     * either way, which the contestant can still type their own cases into.
     *
     * <p><b>Read as whoever is asking.</b> On a deployment with no service account there is no
     * other identity available, and the sample routes are as permission-sensitive as everything
     * else on the judge — a null here would fetch anonymously and quietly return nothing for
     * every problem in the contest.
     *
     * <p>The cache stays keyed on the problem rather than on the caller, because sample data is
     * the same document for anyone allowed to read it and a per-contestant copy would multiply
     * by the size of the room. What that costs is the case where one account cannot read what
     * another can, and {@link #EMPTY_TTL} is the answer to it: a successful read is held for
     * hours, an empty one for a minute. Long enough to stop four routes being probed on every
     * statement open, short enough that one refused account cannot suppress a contest's samples
     * for everybody for the rest of the round.
     */
    public List<PracticeDto.Sample> samples(DomjudgeCredentialStore.Stored as, String contestId,
                                            String problemId) {
        String key = contestId + "/" + problemId;
        Entry hit = cache.get(key);
        if (hit != null) {
            Duration ttl = hit.samples().isEmpty() ? EMPTY_TTL : TTL;
            if (Instant.now().isBefore(hit.at().plus(ttl))) return hit.samples();
        }

        List<PracticeDto.Sample> found = fetch(as, contestId, problemId);
        cache.put(key, new Entry(found, Instant.now()));
        return found;
    }

    private List<PracticeDto.Sample> fetch(DomjudgeCredentialStore.Stored as, String contestId,
                                           String problemId) {
        Route remembered = known.get();
        if (remembered != null) {
            List<PracticeDto.Sample> viaKnown = tryRoute(as, remembered, contestId, problemId);
            if (!viaKnown.isEmpty()) return viaKnown;
            // The remembered route stopped working — fall through and probe again rather than
            // reporting no samples, since a contest can hold problems imported different ways.
        }

        for (Route route : Route.values()) {
            if (route == remembered) continue;
            List<PracticeDto.Sample> found = tryRoute(as, route, contestId, problemId);
            if (!found.isEmpty()) {
                if (known.compareAndSet(remembered, route)) {
                    log.info("DOMjudge sample data resolved via {} — using it for later problems",
                        route);
                }
                return found;
            }
        }

        log.warn("No sample data found for problem {} in contest {}. Tried: {}. "
                + "Run scripts/domjudge-probe.sh against the instance to see what it exposes.",
            problemId, contestId, List.of(Route.values()));
        return List.of();
    }

    private List<PracticeDto.Sample> tryRoute(DomjudgeCredentialStore.Stored as, Route route,
                                              String contestId, String problemId) {
        try {
            return switch (route) {
                case API_CONTEST_TESTCASES -> viaTestcaseApi(as,
                    "/contests/" + contestId + "/problems/" + problemId + "/testcases",
                    contestId, problemId);
                case API_PROBLEM_TESTCASES -> viaTestcaseApi(as,
                    "/problems/" + problemId + "/testcases", contestId, problemId);
                case WEB_TEAM_SAMPLES_ZIP -> viaZip(as,
                    "/team/problems/" + problemId + "/samples.zip");
                case WEB_PUBLIC_SAMPLES_ZIP -> viaZip(as,
                    "/public/problems/" + problemId + "/samples.zip");
            };
        } catch (Exception e) {
            log.debug("DOMjudge sample route {} did not answer for {}: {}",
                route, problemId, e.getMessage());
            return List.of();
        }
    }

    // ------------------------------------------------------------- API routes

    /**
     * Reads the testcase list, then the content of the sample ones.
     *
     * Content arrives one of two ways depending on the build: inline on the testcase object,
     * or behind a per-file endpoint. Both are handled, inline first because it costs no extra
     * request.
     */
    private List<PracticeDto.Sample> viaTestcaseApi(DomjudgeCredentialStore.Stored as,
                                                    String path, String contestId,
                                                    String problemId) {
        WebClient api = domjudge.apiClient(as);
        List<DjModels.Testcase> testcases = api.get()
            .uri(path)
            .retrieve()
            .bodyToFlux(DjModels.Testcase.class)
            .collectList()
            .block(Duration.ofSeconds(20));

        if (testcases == null || testcases.isEmpty()) return List.of();

        List<DjModels.Testcase> samples = new ArrayList<>(testcases.stream()
            .filter(tc -> Boolean.TRUE.equals(tc.getSample()))
            .toList());
        if (samples.isEmpty()) return List.of();

        samples.sort(Comparator.comparing(
            tc -> tc.getOrdinal() == null ? Integer.MAX_VALUE : tc.getOrdinal()));

        List<PracticeDto.Sample> out = new ArrayList<>(samples.size());
        for (DjModels.Testcase tc : samples) {
            String input = decode(tc.getInput());
            String output = decode(tc.getOutput());

            if (input == null) input = fetchFile(api, path, tc.getId(), "input");
            if (output == null) output = fetchFile(api, path, tc.getId(), "output");

            if (input != null || output != null) {
                out.add(new PracticeDto.Sample(
                    input == null ? "" : input,
                    output == null ? "" : output));
            }
        }
        return out;
    }

    private String fetchFile(WebClient api, String basePath, String testcaseId, String which) {
        if (testcaseId == null) return null;
        try {
            byte[] raw = api.get()
                .uri(basePath + "/" + testcaseId + "/file/" + which)
                .retrieve()
                .bodyToMono(byte[].class)
                .block(Duration.ofSeconds(20));
            return raw == null ? null : new String(raw, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("Testcase {} {} not readable: {}", testcaseId, which, e.getMessage());
            return null;
        }
    }

    /**
     * Inline content, which DOMjudge base64-encodes.
     *
     * Tried as base64 first and accepted only if the result decodes cleanly as UTF-8; test data
     * is text, so a decode that produces control bytes means the field was plain to begin with.
     */
    private String decode(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(raw.trim());
            String text = new String(decoded, StandardCharsets.UTF_8);
            if (looksLikeText(text)) return text;
        } catch (IllegalArgumentException ignored) {
            // Not base64 — the build inlines it as plain text.
        }
        return raw;
    }

    private boolean looksLikeText(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x09 || (c > 0x0D && c < 0x20)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------- zip routes

    /**
     * Unpacks a samples zip into input/answer pairs.
     *
     * Entries are matched on their base name, so {@code 1.in} pairs with {@code 1.ans} however
     * the archive orders them. Both {@code .ans} and {@code .out} are accepted because problem
     * packages in the wild use each.
     */
    private List<PracticeDto.Sample> viaZip(DomjudgeCredentialStore.Stored as, String path)
            throws Exception {
        byte[] archive = domjudge.webClient(as).get()
            .uri(path)
            .retrieve()
            .bodyToMono(byte[].class)
            .block(Duration.ofSeconds(30));

        if (archive == null || archive.length == 0) return List.of();

        Map<String, String> inputs = new LinkedHashMap<>();
        Map<String, String> outputs = new LinkedHashMap<>();

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                String name = entry.getName();
                int slash = name.lastIndexOf('/');
                if (slash >= 0) name = name.substring(slash + 1);

                int dot = name.lastIndexOf('.');
                if (dot <= 0) continue;
                String base = name.substring(0, dot);
                String ext = name.substring(dot + 1).toLowerCase();

                String content = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                if ("in".equals(ext)) inputs.put(base, content);
                else if ("ans".equals(ext) || "out".equals(ext)) outputs.put(base, content);
            }
        }

        List<PracticeDto.Sample> out = new ArrayList<>();
        for (Map.Entry<String, String> in : inputs.entrySet()) {
            out.add(new PracticeDto.Sample(
                in.getValue(), outputs.getOrDefault(in.getKey(), "")));
        }
        return out;
    }

    /** The API client, reached through the same credentials the rest of the integration uses. */

}
