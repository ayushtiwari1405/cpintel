package com.cpintel.compete;

import com.cpintel.archive.SubmissionArchive;
import com.cpintel.exception.ApiException;
import com.cpintel.files.ContestFilePolicy;
import com.cpintel.integration.codeforces.CfModels;
import com.cpintel.integration.codeforces.CfStandingsResponse;
import com.cpintel.integration.codeforces.CfSubmissionsResponse;
import com.cpintel.integration.codeforces.CodeforcesClient;
import com.cpintel.practice.CfSessionStore;
import com.cpintel.practice.CfStatementScraper;
import com.cpintel.practice.CfWebSubmitClient;
import com.cpintel.practice.PracticeDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a Codeforces contest — live or virtual — from a single page.
 *
 * The contest stays entirely on Codeforces. This never registers, never withdraws and never
 * computes a rating; submit nothing and it has touched the account not at all.
 *
 * The important lesson encoded here: the API describes the *contest*, the pages describe the
 * *participant*. A virtual entry leaves the contest's phase at FINISHED while the user's own
 * window counts down, so anything about "can I submit and how long do I have" is read from
 * what Codeforces actually serves this session.
 *
 * <p>This was the whole of {@code CompeteService} until DOMjudge arrived. It moved behind
 * {@link CompeteProvider} unchanged in behaviour — the contest id is now carried as a string
 * and parsed back to an int at the edge of every Codeforces call, because Codeforces is the
 * judge that numbers its contests, not the arena.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CodeforcesCompeteProvider implements CompeteProvider {

    private static final Set<String> PENDING_VERDICTS = Set.of("TESTING", "SUBMITTED");

    /** Accepts a contest URL, a problem/countdown URL inside one, or a bare contest id. */
    private static final Pattern CONTEST_ID = Pattern.compile(
        "(?:contests?|gym)/(\\d+)|^\\s*(\\d{2,7})\\s*$");

    private static final Duration META_TTL = Duration.ofMinutes(10);

    private final CodeforcesClient codeforcesClient;
    private final CfStatementScraper scraper;
    private final CfWebSubmitClient submitClient;
    private final CfSessionStore sessionStore;
    private final CfContestScraper contestScraper;
    private final SubmissionArchive archive;
    private final ContestFilePolicy filePolicy;

    /** contest.list is one call for every contest that exists, so it is worth holding onto. */
    private final Map<Integer, Cached> metaCache = new ConcurrentHashMap<>();

    private record Cached(CfStandingsResponse.Contest contest, Instant at) {}

    @Value("${cpintel.practice.submit-enabled:true}")
    private boolean submitEnabled;

    @Override
    public String platform() {
        return CompeteDto.Platform.CODEFORCES.name();
    }

    // ------------------------------------------------------------ contest load

    @Override
    public String parseContestId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("Paste a Codeforces contest link.");
        }
        Matcher m = CONTEST_ID.matcher(raw.trim());
        if (!m.find()) {
            throw ApiException.badRequest(
                "That does not look like a Codeforces contest link. Expected something like "
                    + "https://codeforces.com/contest/2259");
        }
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    /** Codeforces numbers its contests; anything else never came from {@link #parseContestId}. */
    private int numericId(String contestId) {
        try {
            return Integer.parseInt(contestId.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(
                "'" + contestId + "' is not a Codeforces contest id.");
        }
    }

    /**
     * Combines what the API knows about the contest with what the pages know about this user.
     *
     * Neither alone is enough: the API has the official schedule but thinks a virtual entry is
     * a finished contest, and the pages have the live participant clock but no schedule.
     */
    @Override
    public CompeteDto.ContestInfo contestInfo(Long userId, String rawContestId) {
        int contestId = numericId(rawContestId);
        CfSessionStore.StoredSession session = sessionStore.find(userId);

        CfStandingsResponse.Contest meta = contestMeta(contestId);

        CfContestScraper.ContestPage page = session == null
            ? new CfContestScraper.ContestPage(false, false, null, null, List.of())
            : contestScraper.dashboard(session.cookieHeader(), contestId);

        if (meta == null && !page.reachable()) {
            throw ApiException.notFound("Codeforces has no contest " + contestId
                + ", or it is not visible to this account.");
        }

        String officialPhase = meta == null || meta.getPhase() == null ? "UNKNOWN" : meta.getPhase();
        boolean officialLive = "CODING".equals(officialPhase);

        // Virtual: the contest is over for everyone else, but Codeforces is serving this
        // account a live dashboard with a running clock.
        boolean virtual = !officialLive && page.started() && page.reachable();
        // Registered and waiting: every contest URL is redirecting to the countdown page.
        boolean waiting = page.reachable() && !page.started();

        boolean running = officialLive || virtual;
        String phase = virtual ? "VIRTUAL" : (waiting && !officialLive ? "BEFORE" : officialPhase);

        long duration = meta == null || meta.getDurationSeconds() == null
            ? 0 : meta.getDurationSeconds();
        Instant startsAt = meta == null || meta.getStartTimeSeconds() == null
            ? null : Instant.ofEpochSecond(meta.getStartTimeSeconds());

        // The page clock is authoritative when present — it is the only one that knows about a
        // virtual window. Fall back to the official schedule when there is no session.
        long untilStart;
        long remaining;
        if (page.clockSeconds() != null) {
            untilStart = page.started() ? 0 : page.clockSeconds();
            remaining = page.started() ? page.clockSeconds() : 0;
        } else {
            long now = Instant.now().getEpochSecond();
            untilStart = startsAt == null ? 0 : startsAt.getEpochSecond() - now;
            remaining = startsAt != null && officialLive
                ? Math.max(0, startsAt.getEpochSecond() + duration - now) : 0;
        }

        List<CompeteDto.ContestProblem> problems = new ArrayList<>(page.problems());
        problems.sort(Comparator.comparing(CompeteDto.ContestProblem::index,
            Comparator.nullsLast(Comparator.naturalOrder())));

        boolean canSubmit = false;
        String closedReason = null;
        if (!submitEnabled) {
            closedReason = "Auto-submit is disabled on this deployment.";
        } else if (session == null) {
            closedReason = "Connect your Codeforces session to compete.";
        } else if (waiting) {
            closedReason = "Your contest has not started yet — Codeforces is still counting down.";
        } else {
            // The only trustworthy answer: does CF render the problem select for this account.
            canSubmit = submitClient.canSubmitTo(session.cookieHeader(), session.userAgent(), contestId);
            if (!canSubmit) {
                closedReason = switch (officialPhase) {
                    case "FINISHED" -> "This contest has finished. Start a virtual "
                        + "participation on Codeforces to compete on it.";
                    case "PENDING_SYSTEM_TEST", "SYSTEM_TEST" -> "The contest is being system-tested.";
                    default -> "Codeforces is not accepting submissions from this account — "
                        + "check you are registered for this contest.";
                };
            }
        }

        // Prefer the API's name — it is authoritative and unaffected by page chrome.
        String name = meta != null && meta.getName() != null ? meta.getName()
            : (page.name() != null ? page.name() : "Contest " + contestId);

        return new CompeteDto.ContestInfo(
            String.valueOf(contestId), name, CompeteDto.Platform.CODEFORCES.name(),
            phase, running, meta != null && Boolean.TRUE.equals(meta.getFrozen()),
            startsAt, duration, untilStart, remaining,
            canSubmit, closedReason,
            filePolicy.enabledFor(CompeteDto.Platform.CODEFORCES.name(),
                String.valueOf(contestId)),
            CompeteDto.StatementFormat.HTML,
            problems,
            "https://codeforces.com/contest/" + contestId);
    }

    private CfStandingsResponse.Contest contestMeta(int contestId) {
        Cached hit = metaCache.get(contestId);
        if (hit != null && Instant.now().isBefore(hit.at().plus(META_TTL))) {
            return hit.contest();
        }
        try {
            CfStandingsResponse.Contest meta = codeforcesClient.getContestMeta(contestId);
            if (meta != null) metaCache.put(contestId, new Cached(meta, Instant.now()));
            return meta;
        } catch (Exception e) {
            log.debug("Contest {} metadata lookup failed: {}", contestId, e.getMessage());
            return hit == null ? null : hit.contest();
        }
    }

    // -------------------------------------------------------------- statements

    @Override
    public PracticeDto.ProblemDetail statement(Long userId, String rawContestId, String index) {
        int contestId = numericId(rawContestId);
        CfSessionStore.StoredSession session = sessionStore.find(userId);
        String cookies = session == null ? null : session.cookieHeader();
        String userAgent = session == null ? null : session.userAgent();
        return scraper.fetchForContest(contestId, index, cookies, userAgent);
    }

    @Override
    public List<PracticeDto.LanguageOption> languages(Long userId, String rawContestId) {
        int contestId = numericId(rawContestId);
        CfSessionStore.StoredSession session = sessionStore.find(userId);
        if (session == null) return List.of();
        try {
            return submitClient.scrapeContestLanguages(session.cookieHeader(), session.userAgent(), contestId);
        } catch (Exception e) {
            log.debug("Contest {} language scrape failed: {}", contestId, e.getMessage());
            return List.of();
        }
    }

    // ------------------------------------------------------------- submitting

    @Override
    public CompeteDto.ContestSubmission submit(Long userId, String rawContestId,
                                               CompeteDto.ContestSubmitRequest req) {
        int contestId = numericId(rawContestId);
        if (!submitEnabled) {
            throw ApiException.forbidden("Auto-submit is disabled on this deployment.");
        }
        if (req.source().isBlank()) {
            throw ApiException.badRequest("There is no code to submit.");
        }

        CfSessionStore.StoredSession session = sessionStore.require(userId);

        // Written before the code leaves the building. Mid-contest this is the only reason a
        // refused submission is recoverable at all — the user cannot open Codeforces to go
        // and copy it back.
        String archiveId = archive.recordAttempt(userId, SubmissionArchive.CODEFORCES,
            String.valueOf(contestId), req.index(), null, req.languageId(), null, req.source());

        long submissionId;
        try {
            submissionId = submitClient.submitToContest(session.cookieHeader(), session.userAgent(), contestId,
                req.index(), req.languageId(), req.source());
        } catch (ApiException e) {
            if ("CF_SESSION_INVALID".equals(e.getCode())) {
                sessionStore.delete(userId);
            }
            archive.markRejected(archiveId, e.getMessage());
            throw e;
        }
        archive.attachExternalId(archiveId, submissionId);

        try {
            Thread.sleep(1200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String wanted = String.valueOf(submissionId);
        return submissions(userId, rawContestId).stream()
            .filter(s -> wanted.equals(s.id()))
            .findFirst()
            .orElse(new CompeteDto.ContestSubmission(
                wanted, req.index().toUpperCase(), null, null, "TESTING",
                null, null, null, Instant.now(), false,
                "https://codeforces.com/contest/" + contestId + "/submission/" + submissionId));
    }

    /**
     * This user's submissions in the contest, newest first.
     *
     * contest.status still accepts a handle — it is only contest.standings that was locked
     * down — so this stays a cheap API call rather than a scrape.
     */
    @Override
    public List<CompeteDto.ContestSubmission> submissions(Long userId, String rawContestId) {
        int contestId = numericId(rawContestId);
        CfSessionStore.StoredSession session = sessionStore.require(userId);

        CfSubmissionsResponse resp;
        try {
            resp = codeforcesClient.getContestStatus(contestId, session.handle());
        } catch (Exception e) {
            log.debug("Contest {} status failed: {}", contestId, e.getMessage());
            return List.of();
        }
        if (resp == null || resp.getResult() == null) return List.of();

        // One pass to keep the archived copies' verdicts honest, so code read back later
        // still carries the result it actually got.
        archive.syncVerdicts(userId, SubmissionArchive.CODEFORCES,
            String.valueOf(contestId), resp.getResult());

        List<CompeteDto.ContestSubmission> out = new ArrayList<>();
        for (CfModels.Submission s : resp.getResult()) {
            String verdict = s.getVerdict() == null ? "TESTING" : s.getVerdict();
            out.add(new CompeteDto.ContestSubmission(
                s.getId() == null ? null : String.valueOf(s.getId()),
                s.getProblem() == null ? null : s.getProblem().getIndex(),
                s.getProblem() == null ? null : s.getProblem().getName(),
                s.getProgrammingLanguage(),
                verdict,
                s.getPassedTestCount(),
                s.getTimeConsumedMillis(),
                s.getMemoryConsumedBytes(),
                s.getCreationTimeSeconds() == null
                    ? null : Instant.ofEpochSecond(s.getCreationTimeSeconds()),
                isFinished(verdict),
                "https://codeforces.com/contest/" + contestId + "/submission/" + s.getId()));
        }
        return out;
    }

    // ------------------------------------------------------------------- rank

    /**
     * Live rank for the signed-in handle, scraped from the standings page.
     *
     * The API route is closed: contest.standings rejects a handles filter, and the bare call
     * returns every row. The participant view used for virtual entries is a couple of hundred
     * rows instead. Solved count is computed from the user's own submissions so the page still
     * shows something useful before the board picks them up.
     */
    @Override
    public CompeteDto.RankInfo rank(Long userId, String rawContestId) {
        int contestId = numericId(rawContestId);
        CfSessionStore.StoredSession session = sessionStore.require(userId);

        Set<String> solved = new HashSet<>();
        for (CompeteDto.ContestSubmission s : submissions(userId, rawContestId)) {
            if ("OK".equals(s.verdict()) && s.index() != null) solved.add(s.index());
        }

        CfStandingsResponse.Contest meta = contestMeta(contestId);
        boolean virtual = meta == null || !"CODING".equals(meta.getPhase());

        try {
            return contestScraper.rank(session.cookieHeader(), contestId, session.handle(),
                virtual, solved.size());
        } catch (Exception e) {
            log.debug("Contest {} rank scrape failed: {}", contestId, e.getMessage());
            return new CompeteDto.RankInfo(null, null, null, solved.size(), false, true,
                Instant.now());
        }
    }

    private boolean isFinished(String verdict) {
        return verdict != null && !PENDING_VERDICTS.contains(verdict.toUpperCase(Locale.ROOT));
    }
}
