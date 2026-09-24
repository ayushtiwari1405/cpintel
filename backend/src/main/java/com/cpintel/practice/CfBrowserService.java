package com.cpintel.practice;

import com.cpintel.archive.SubmissionArchive;
import com.cpintel.exception.ApiException;
import com.cpintel.integration.codeforces.CfModels;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Codeforces through the user's own browser.
 *
 * <h2>Why</h2>
 *
 * <p>Codeforces serves its website from behind Cloudflare, which ties the clearance a browser
 * earns to that browser. A server replaying the user's cookies from its own address gets the
 * challenge page instead — so on a hosted deployment, statements, the compiler list and
 * submitting all failed however fresh the session was. They worked in development only because
 * the backend and the browser shared a machine.
 *
 * <h2>How</h2>
 *
 * <p>The browser makes the requests — the CPIntel extension on the website, the app's own
 * Codeforces session on the desktop — and this service does everything else: reads the pages it
 * fetched, builds the form it should post, and records the submission in the code archive. What
 * Codeforces' pages look like is known here and nowhere else, exactly as for a server fetch.
 *
 * <p>The browser can send anything, so nothing it sends is trusted beyond the user who sent it:
 * a statement it fetched is shown to them and never cached for anyone else, and archive rows are
 * checked to be theirs before they are touched.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CfBrowserService {

    private final CfWebSubmitClient submitClient;
    private final CfStatementScraper scraper;
    private final CfSessionStore sessionStore;
    private final PracticeService practice;
    private final SubmissionArchive archive;

    /**
     * Links the handle signed in on the user's browser. CPIntel keeps the handle — which the
     * public API needs for verdicts, submissions and rank — and no cookies.
     */
    public PracticeDto.SessionStatus connect(Long userId, String pageHtml) {
        String handle = submitClient.handleOn(pageHtml);
        if (handle == null) {
            throw ApiException.badRequest("This browser is not signed in to Codeforces. Sign in "
                + "at codeforces.com, then connect again.");
        }
        sessionStore.save(userId, handle, null, null);
        return practice.sessionStatus(userId);
    }

    /** A statement page the browser fetched, read for this user only. */
    public PracticeDto.ProblemDetail statement(int contestId, String index, boolean contest,
                                               String pageHtml) {
        Integer rating = null;
        List<String> tags = List.of();
        if (!contest) {
            CfModels.Submission.Problem meta = practice.problemMeta(contestId, index);
            if (meta != null) {
                rating = meta.getRating();
                tags = meta.getTags() == null ? List.of() : meta.getTags();
            }
        }
        return scraper.parsePage(pageHtml, contestId, index, rating, tags, contest);
    }

    /** The compilers on a submit page the browser fetched. */
    public List<PracticeDto.LanguageOption> languages(String pageHtml) {
        return submitClient.languagesOn(pageHtml);
    }

    public String submitPageUrl(int contestId, boolean contest) {
        return submitClient.submitPageUrl(contestId, contest);
    }

    /**
     * The form to post for a submission, with the code archived first — so a submission the
     * browser never manages to send still leaves the user their code.
     */
    public BrowserSubmission prepare(Long userId, int contestId, String index, boolean contest,
                                     String languageId, String source, String submitPageHtml) {
        if (source == null || source.isBlank()) {
            throw ApiException.badRequest("There is no code to submit.");
        }
        CfWebSubmitClient.BrowserForm form = submitClient.submitForm(
            submitPageHtml, contestId, index, contest, languageId, source);
        String archiveId = archive.recordAttempt(userId, SubmissionArchive.CODEFORCES,
            String.valueOf(contestId), index, null, languageId, null, source);
        return new BrowserSubmission(archiveId, form.url(), form.fields());
    }

    /** Reads the new submission's id off Codeforces' answer, and files it with the code. */
    public long complete(Long userId, String archiveId, boolean contest, String resultHtml) {
        // Throws not-found unless the row is this user's.
        archive.source(userId, archiveId);
        try {
            long id = submitClient.submittedId(resultHtml, contest);
            archive.attachExternalId(archiveId, id);
            return id;
        } catch (ApiException e) {
            archive.markRejected(archiveId, e.getMessage());
            throw e;
        }
    }

    /** What the browser should post, and the archive row to report back against. */
    public record BrowserSubmission(String archiveId, String url,
                                    java.util.Map<String, String> fields) {}
}
