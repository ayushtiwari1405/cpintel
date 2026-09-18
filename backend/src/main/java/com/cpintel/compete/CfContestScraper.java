package com.cpintel.compete;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads contest state off the Codeforces contest pages.
 *
 * This exists because the API cannot answer the questions the compete page actually asks.
 * {@code contest.standings} now rejects every extra parameter for non-gym contests — only a
 * bare anonymous call is allowed, and that returns the entire ranklist (8.9 MB and 11k rows
 * for a finished Div. 4), which is useless to poll for one person's rank.
 *
 * More importantly, the API describes the *contest*, not the *participant*. During virtual
 * participation the contest's phase stays FINISHED forever while the user has a private
 * window that is very much running. Only the pages know that, so the pages are what we read.
 */
@Component
@Slf4j
public class CfContestScraper {

    private static final String UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/124.0 Safari/537.36";

    /** CF renders the timer as H:MM:SS — before the start it counts down to it, then down to the end. */
    private static final Pattern CLOCK = Pattern.compile("(\\d+):(\\d{2}):(\\d{2})");

    @Value("${cpintel.platforms.codeforces.web-url:https://codeforces.com}")
    private String webUrl;

    /**
     * What Codeforces is showing this session for a contest.
     *
     * @param started    false while the countdown page is up, true once the dashboard renders
     * @param clockSeconds the countdown value: time until start when not started, time
     *                     remaining when started. Null if CF rendered no timer.
     */
    public record ContestPage(
        boolean reachable,
        boolean started,
        Long clockSeconds,
        String name,
        List<CompeteDto.ContestProblem> problems
    ) {}

    /**
     * Loads the contest dashboard.
     *
     * Before a virtual window opens, every contest URL redirects to a Countdown page — that is
     * how "registered but not started" is detected, since no API reports it.
     */
    public ContestPage dashboard(String cookieHeader, int contestId) {
        Document doc = fetch(cookieHeader, webUrl + "/contest/" + contestId);
        if (doc == null) {
            return new ContestPage(false, false, null, null, List.of());
        }

        Long clock = clockSeconds(doc);
        String title = doc.title() == null ? "" : doc.title();
        boolean countdownPage = title.startsWith("Countdown");

        // The problems table only exists once the contest is open to this account.
        List<CompeteDto.ContestProblem> problems = new ArrayList<>();
        for (Element row : doc.select("table.problems tr")) {
            Element id = row.selectFirst("td.id a");
            if (id == null) continue;
            String index = id.text().trim();
            if (index.isEmpty()) continue;

            String name = null;
            Element nameCell = row.select("td").size() > 1 ? row.select("td").get(1) : null;
            if (nameCell != null) {
                Element link = nameCell.selectFirst("a");
                if (link != null) name = link.text().trim();
            }
            problems.add(new CompeteDto.ContestProblem(index.toUpperCase(), name, null, null));
        }

        return new ContestPage(true, !countdownPage, clock, contestName(doc), problems);
    }

    /**
     * The signed-in handle's row in the standings.
     *
     * Uses the participant view for virtual entries, which is a couple of hundred rows rather
     * than the whole ranklist. The row is absent until the first submission lands, which is
     * normal rather than an error.
     */
    public CompeteDto.RankInfo rank(String cookieHeader, int contestId, String handle,
                                    boolean virtual, int solvedFallback) {
        String path = virtual
            ? "/contest/" + contestId + "/standings/participant/true"
            : "/contest/" + contestId + "/standings";

        Document doc = fetch(cookieHeader, webUrl + path);
        if (doc != null) {
            for (Element row : doc.select("table.standings tr")) {
                if (row.select("a[href*=/profile/" + handle + "]").isEmpty()) continue;

                List<Element> tds = row.select("td");
                Integer rank = tds.isEmpty() ? null : intOf(tds.get(0).text());
                Integer solved = tds.size() > 2 ? intOf(tds.get(2).text()) : null;
                Integer penalty = tds.size() > 3 ? intOf(tds.get(3).text()) : null;

                return new CompeteDto.RankInfo(rank,
                    solved == null ? null : solved.doubleValue(),
                    penalty, solved != null ? solved : solvedFallback,
                    false, true, java.time.Instant.now());
            }
        }
        // Not on the board yet — still report what we know from the user's own submissions.
        return new CompeteDto.RankInfo(null, null, null, solvedFallback, false, true,
            java.time.Instant.now());
    }

    // ---------------------------------------------------------------- helpers

    private Document fetch(String cookieHeader, String url) {
        try {
            var conn = Jsoup.connect(url)
                .userAgent(UA)
                .header("Accept-Language", "en")
                .timeout(25_000)
                .maxBodySize(0);
            if (cookieHeader != null && !cookieHeader.isBlank()) {
                conn = conn.header("Cookie", cookieHeader);
            }
            return conn.get();
        } catch (Exception e) {
            log.debug("Contest page fetch failed {}: {}", url, e.getMessage());
            return null;
        }
    }

    private Long clockSeconds(Document doc) {
        Element el = doc.selectFirst("span.countdown");
        if (el == null) return null;
        Matcher m = CLOCK.matcher(el.text());
        if (!m.find()) return null;
        return Long.parseLong(m.group(1)) * 3600
            + Long.parseLong(m.group(2)) * 60
            + Long.parseLong(m.group(3));
    }

    /**
     * "Dashboard - Codeforces Round 1090 (Div. 4) - Codeforces" -> the middle part.
     *
     * Read from the page title rather than a link in the body: the sidebar carries links like
     * "Clone Contest to Mashup" that a href-shaped selector happily matches instead.
     */
    private String contestName(Document doc) {
        String title = doc.title();
        if (title == null || title.isBlank()) return null;
        String t = title.replaceFirst("^(Dashboard|Countdown|Submit Code|Status)\\s*-\\s*", "")
            .replaceFirst("\\s*-\\s*Codeforces\\s*$", "");
        return t.isBlank() ? null : t;
    }

    private Integer intOf(String text) {
        if (text == null) return null;
        String digits = text.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return null;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
