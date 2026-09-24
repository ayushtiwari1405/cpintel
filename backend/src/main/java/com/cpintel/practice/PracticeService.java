package com.cpintel.practice;

import com.cpintel.archive.SubmissionArchive;
import com.cpintel.exception.ApiException;
import com.cpintel.integration.codeforces.CfModels;
import com.cpintel.integration.codeforces.CfProblemsetClient;
import com.cpintel.integration.codeforces.CfSubmissionsResponse;
import com.cpintel.integration.codeforces.CodeforcesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PracticeService {

    private static final Set<String> PENDING_VERDICTS = Set.of(
        "TESTING", "SUBMITTED", "PARTIAL");

    private final CfProblemsetClient problemsetClient;
    private final CodeforcesClient codeforcesClient;
    private final CfStatementScraper scraper;
    private final CfWebSubmitClient submitClient;
    private final CfSessionStore sessionStore;
    private final SubmissionArchive archive;

    @Value("${cpintel.practice.submit-enabled:true}")
    private boolean submitEnabled;

    @Value("${cpintel.practice.default-languages}")
    private String defaultLanguages;

    // -------------------------------------------------------------- discovery

    public List<PracticeDto.ProblemSummary> search(String query, Integer minRating,
                                                   Integer maxRating, String tag, int limit) {
        String q = query == null ? null : query.trim().toLowerCase(Locale.ROOT);

        Comparator<CfModels.Submission.Problem> newestFirst =
            Comparator.<CfModels.Submission.Problem, Integer>comparing(
                    CfModels.Submission.Problem::getContestId, Comparator.reverseOrder())
                .thenComparing(CfModels.Submission.Problem::getIndex);

        return problemsetClient.getAllProblems().stream()
            .filter(p -> p.getContestId() != null && p.getIndex() != null)
            .filter(p -> minRating == null || (p.getRating() != null && p.getRating() >= minRating))
            .filter(p -> maxRating == null || (p.getRating() != null && p.getRating() <= maxRating))
            .filter(p -> tag == null || tag.isBlank()
                || (p.getTags() != null && p.getTags().contains(tag)))
            .filter(p -> q == null || q.isEmpty() || matches(p, q))
            .sorted(newestFirst)
            .limit(limit)
            .map(this::toSummary)
            .collect(Collectors.toList());
    }

    private boolean matches(CfModels.Submission.Problem p, String q) {
        String code = (p.getContestId() + String.valueOf(p.getIndex())).toLowerCase(Locale.ROOT);
        if (code.startsWith(q)) return true;
        return p.getName() != null && p.getName().toLowerCase(Locale.ROOT).contains(q);
    }

    private PracticeDto.ProblemSummary toSummary(CfModels.Submission.Problem p) {
        return new PracticeDto.ProblemSummary(
            p.getContestId(), p.getIndex(), p.getName(), p.getRating(),
            p.getTags() == null ? List.of() : p.getTags(),
            scraper.problemUrl(p.getContestId(), p.getIndex()));
    }

    public List<String> allTags() {
        return problemsetClient.getAllProblems().stream()
            .filter(p -> p.getTags() != null)
            .flatMap(p -> p.getTags().stream())
            .distinct().sorted().collect(Collectors.toList());
    }

    /**
     * Statement, constraints and samples for one problemset problem.
     *
     * <p>The user's Codeforces session goes with the request when they have one connected. Not
     * for authorisation -- these pages are public -- but because Codeforces serves its HTML
     * from behind a Cloudflare interstitial that answers a plain HTTP client with 403, and the
     * {@code cf_clearance} cookie their browser earned is what gets past it. Without a session
     * the fetch still happens and still degrades to metadata plus a link out, which is the best
     * that can be done anonymously.
     */
    public PracticeDto.ProblemDetail getProblem(Long userId, int contestId, String index) {
        return getProblem(userId, contestId, index, false);
    }

    /**
     * @param cachedOnly answer from the cache or not at all, with issue NOT_CACHED on a miss.
     *                   For a page that will fetch the statement through the user's browser:
     *                   a server fetch would only reach Cloudflare's challenge.
     */
    public PracticeDto.ProblemDetail getProblem(Long userId, int contestId, String index,
                                                boolean cachedOnly) {
        CfModels.Submission.Problem meta = problemMeta(contestId, index);
        Integer rating = meta == null ? null : meta.getRating();
        List<String> tags = meta == null || meta.getTags() == null ? List.of() : meta.getTags();

        if (cachedOnly) {
            PracticeDto.ProblemDetail hit = scraper.cached(contestId, index, rating, tags);
            if (hit != null) return hit;
            return new PracticeDto.ProblemDetail(String.valueOf(contestId), index.toUpperCase(),
                meta != null && meta.getName() != null ? meta.getName() : contestId + index,
                rating, tags, null, null, null, null, null, null, null, null, List.of(),
                scraper.problemUrl(contestId, index), false, null, "NOT_CACHED");
        }

        CfSessionStore.StoredSession session = userId == null ? null : sessionStore.find(userId);
        return scraper.fetch(contestId, index, rating, tags,
            session == null ? null : session.cookieHeader(),
            session == null ? null : session.userAgent());
    }

    /** The problemset API's entry for a problem — rating, tags, name — or null. */
    public CfModels.Submission.Problem problemMeta(int contestId, String index) {
        return problemsetClient.getAllProblems().stream()
            .filter(p -> contestId == (p.getContestId() == null ? -1 : p.getContestId()))
            .filter(p -> index.equalsIgnoreCase(p.getIndex()))
            .findFirst().orElse(null);
    }

    // ---------------------------------------------------------------- session

    public PracticeDto.SessionStatus sessionStatus(Long userId) {
        CfSessionStore.StoredSession s = sessionStore.find(userId);
        if (s == null) {
            return new PracticeDto.SessionStatus(false, null, null, null, submitEnabled, false);
        }
        return new PracticeDto.SessionStatus(
            true, s.handle(), s.linkedAt(), s.expiresAt(), submitEnabled,
            s.cookieHeader() == null || s.cookieHeader().isBlank());
    }

    /**
     * Verifies the pasted cookies actually belong to a logged-in Codeforces account before
     * storing them. Storing an unverified blob would just move the failure to submit time,
     * where it is far more annoying.
     */
    public PracticeDto.SessionStatus connectSession(Long userId, String rawCookieHeader) {
        return connectSession(userId, rawCookieHeader, null, null);
    }

    /**
     * @param userAgent the User-Agent of the browser the cookies came from. Cloudflare binds
     *                  {@code cf_clearance} to it, so verification and every later request have
     *                  to present the same string; without it Codeforces answers with its
     *                  interstitial and the session reads as signed out however valid it is.
     */
    public PracticeDto.SessionStatus connectSession(Long userId, String rawCookieHeader,
                                                    String expectedHandle, String userAgent) {
        String cookies = CfSessionStore.sanitiseCookieHeader(rawCookieHeader);

        String handle = submitClient.resolveHandle(cookies, userAgent);
        if (handle == null) {
            throw ApiException.unauthorized(
                "Codeforces did not recognise that session as signed in. If you are signed in "
                    + "at codeforces.com, this is usually Cloudflare refusing the request "
                    + "because it did not come from the same browser the cookies belong to — "
                    + "reconnect from that browser.");
        }
        // Guards against connecting the wrong account when several are signed in across
        // browsers or profiles.
        if (expectedHandle != null && !expectedHandle.isBlank()
            && !expectedHandle.trim().equalsIgnoreCase(handle)) {
            throw ApiException.badRequest("That browser is signed in to Codeforces as "
                + handle + ", not " + expectedHandle.trim() + ".");
        }

        sessionStore.save(userId, handle, cookies, userAgent);
        return sessionStatus(userId);
    }

    public void disconnectSession(Long userId) {
        sessionStore.delete(userId);
    }

    // -------------------------------------------------------------- languages

    public List<PracticeDto.LanguageOption> languages(Long userId) {
        CfSessionStore.StoredSession s = sessionStore.find(userId);
        if (s != null) {
            try {
                List<PracticeDto.LanguageOption> scraped =
                    submitClient.scrapeLanguages(s.cookieHeader(), s.userAgent());
                if (!scraped.isEmpty()) return scraped;
            } catch (Exception e) {
                log.debug("Language scrape failed for user {}: {}", userId, e.getMessage());
            }
        }
        return parseDefaultLanguages();
    }

    /**
     * Fallback list used before a session is connected. Codeforces reshuffles programTypeId
     * values whenever compilers change, so these are a starting point only — once a session
     * exists we read the real dropdown off the submit page instead.
     */
    private List<PracticeDto.LanguageOption> parseDefaultLanguages() {
        List<PracticeDto.LanguageOption> out = new ArrayList<>();
        // Semicolon-separated because the labels contain commas ("G++23 (64 bit, winlibs)").
        for (String pair : defaultLanguages.split(";")) {
            String[] parts = pair.split(":", 2);
            if (parts.length == 2) {
                out.add(new PracticeDto.LanguageOption(parts[0].trim(), parts[1].trim()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------- submitting

    public PracticeDto.SubmitResponse submit(Long userId, PracticeDto.SubmitRequest req) {
        if (!submitEnabled) {
            throw ApiException.forbidden("Auto-submit is disabled on this deployment.");
        }
        if (req.source().isBlank()) {
            throw ApiException.badRequest("There is no code to submit.");
        }

        CfSessionStore.StoredSession session = sessionStore.require(userId);

        // Archived before it is sent, so a refused or unconfirmed submission still leaves the
        // user their code. The compiler label is left null on purpose: filling it in means
        // scraping the Codeforces submit page, and nothing on the submit path should wait on
        // the network for a field the submissions list fills in for free.
        String archiveId = archive.recordAttempt(userId, SubmissionArchive.CODEFORCES,
            String.valueOf(req.contestId()), req.index(), null, req.languageId(), null,
            req.source());

        long submissionId;
        try {
            submissionId = submitClient.submit(session.cookieHeader(), session.userAgent(), req.contestId(),
                req.index(), req.languageId(), req.source());
        } catch (ApiException e) {
            // A dead session cannot be repaired without the user — drop it so the UI stops
            // claiming the account is connected.
            if ("CF_SESSION_INVALID".equals(e.getCode())) {
                sessionStore.delete(userId);
            }
            archive.markRejected(archiveId, e.getMessage());
            throw e;
        }
        archive.attachExternalId(archiveId, submissionId);

        PracticeDto.VerdictResponse verdict = pollOnce(userId, session.handle(), submissionId);

        return new PracticeDto.SubmitResponse(
            true, submissionId, verdict.verdict(), verdict.passedTestCount(),
            verdict.timeConsumedMillis(), verdict.memoryConsumedBytes(),
            "Submitted to Codeforces as " + session.handle(),
            "https://codeforces.com/submissions/" + session.handle());
    }

    /** Single-shot verdict read; the frontend polls this until finished is true. */
    public PracticeDto.VerdictResponse verdict(Long userId, long submissionId) {
        CfSessionStore.StoredSession session = sessionStore.require(userId);
        return readVerdict(userId, session.handle(), submissionId);
    }

    private PracticeDto.VerdictResponse pollOnce(Long userId, String handle, long submissionId) {
        // A short pause so the immediate response usually carries something more useful than
        // "SUBMITTED"; the client keeps polling from there.
        try {
            Thread.sleep(1200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return readVerdict(userId, handle, submissionId);
    }

    private PracticeDto.VerdictResponse readVerdict(Long userId, String handle,
                                                    long submissionId) {
        try {
            CfSubmissionsResponse resp = codeforcesClient.getSubmissions(handle, 1, 20);
            if (resp != null && resp.getResult() != null) {
                for (CfModels.Submission s : resp.getResult()) {
                    if (s.getId() != null && s.getId() == submissionId) {
                        String v = s.getVerdict() == null ? "TESTING" : s.getVerdict();
                        archive.updateVerdict(userId, SubmissionArchive.CODEFORCES,
                            submissionId, v, s.getPassedTestCount(),
                            s.getTimeConsumedMillis(), s.getMemoryConsumedBytes());
                        return new PracticeDto.VerdictResponse(String.valueOf(submissionId), v,
                            s.getPassedTestCount(), s.getTimeConsumedMillis(),
                            s.getMemoryConsumedBytes(), isFinished(v));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Verdict lookup failed for {}: {}", submissionId, e.getMessage());
        }
        return new PracticeDto.VerdictResponse(
            String.valueOf(submissionId), "TESTING", null, null, null, false);
    }

    private boolean isFinished(String verdict) {
        return verdict != null && !PENDING_VERDICTS.contains(verdict.toUpperCase(Locale.ROOT));
    }
}
