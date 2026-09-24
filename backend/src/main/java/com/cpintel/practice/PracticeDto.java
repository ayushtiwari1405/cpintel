package com.cpintel.practice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * DTOs for the practice arena (Codeforces problem fetch + submit).
 */
public class PracticeDto {

    /** Lightweight row used by the problem picker. */
    public record ProblemSummary(
        Integer contestId,
        String index,
        String name,
        Integer rating,
        List<String> tags,
        String url
    ) {}

    public record Sample(String input, String output) {}

    /** Full scraped statement. All HTML fields are sanitised server-side. */
    public record ProblemDetail(
        String contestId,
        String index,
        String name,
        Integer rating,
        List<String> tags,
        String timeLimit,
        String memoryLimit,
        String inputFile,
        String outputFile,
        String legendHtml,
        String inputSpecHtml,
        String outputSpecHtml,
        String noteHtml,
        List<Sample> samples,
        String url,
        boolean statementAvailable,
        /**
         * Where to fetch the statement PDF, for judges that publish one instead of HTML.
         *
         * Null on Codeforces, which renders the statement inline. Points at CPIntel's own
         * proxy rather than the judge: a contestant's browser has no credentials for a
         * DOMjudge behind admin auth, and sending them there would also take them out of a
         * window the round is watching.
         */
        String statementPdfUrl,

        /**
         * Why the statement is missing, when it is — {@code null} whenever one was read.
         *
         * <p>The UI used to have one sentence for every failure, guessing at "gym-only, or the
         * site is rate-limiting us". Neither is the usual cause. Codeforces serves its HTML from
         * behind a browser check, and the {@code cf_clearance} cookie that records having passed
         * it is short-lived — far shorter than the fortnight a stored CPIntel session lives. So
         * the ordinary failure is a session that is still on file and no longer clears the
         * check, and the only thing that fixes it is reconnecting. Saying so is the difference
         * between a dead end and a next step.
         *
         * <p>{@code SESSION_STALE}, {@code NO_SESSION}, {@code NOT_FOUND} or {@code UNAVAILABLE}.
         */
        String statementIssue
    ) {}

    public record LanguageOption(String id, String label) {}

    // ------------------------------------------------------------- session

    /**
     * Raw value of the Cookie header copied from a browser that is logged in to Codeforces.
     * Taking the whole header rather than named fields means we pick up whatever CF is
     * currently using — JSESSIONID, X-User-Sha1, the 39ce7 fingerprint, RCPC — without
     * needing to track which ones matter this month.
     */
    public record SessionRequest(
        @NotBlank @Size(max = 8_000) String cookieHeader,
        /**
         * Handle the caller believes this session belongs to, if known.
         *
         * Checked server-side because only the server can verify it: Codeforces answers the
         * local helper with a Cloudflare interstitial, so the helper cannot read the handle
         * itself. When set, a session belonging to anyone else is refused rather than stored.
         */
        @Size(max = 64) String expectedHandle,

        /**
         * User-Agent of the browser these cookies were taken from.
         *
         * Cloudflare binds the {@code cf_clearance} cookie to the User-Agent that solved its
         * challenge, so a session replayed under any other string is answered with the
         * interstitial and reads as signed out. The helper sends the browser's own; a manual
         * paste may omit it, and then a default is used.
         */
        @Size(max = 512) String userAgent
    ) {}

    public record SessionStatus(
        boolean connected,
        String handle,
        Instant linkedAt,
        Instant expiresAt,
        boolean submitEnabled,
        /**
         * Linked through the user's own browser: CPIntel knows the handle and holds no
         * Codeforces cookies, and pages are fetched by the browser (extension or desktop app).
         */
        boolean viaBrowser
    ) {}

    // ---------------------------------------------------------- submission

    public record SubmitRequest(
        @NotNull Integer contestId,
        @NotBlank @Size(max = 8) String index,
        @NotBlank String languageId,
        @NotBlank @Size(max = 65_536) String source
    ) {}

    public record SubmitResponse(
        boolean accepted,
        Long submissionId,
        String verdict,
        Integer passedTestCount,
        Integer timeConsumedMillis,
        Long memoryConsumedBytes,
        String message,
        String statusUrl
    ) {}

    public record VerdictResponse(
        /** Text, so the same shape carries a Codeforces number and a DOMjudge id alike. */
        String submissionId,
        String verdict,
        Integer passedTestCount,
        Integer timeConsumedMillis,
        Long memoryConsumedBytes,
        boolean finished
    ) {}
}
