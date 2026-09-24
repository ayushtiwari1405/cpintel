package com.cpintel.compete;

import com.cpintel.events.ExamSessionRecorder;
import com.cpintel.exception.ApiException;
import com.cpintel.files.ContestFilePolicy;
import com.cpintel.groups.LanguagePolicy;
import com.cpintel.groups.ProctoringGate;
import com.cpintel.files.FilesDto;
import com.cpintel.files.PersonalFileService;
import com.cpintel.practice.PracticeDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The compete arena, over whichever judge the contest is on.
 *
 * <p>This holds no contest logic of its own. Every judge-specific decision — what a contest id
 * looks like, how a phase is determined, how a submission is sent — lives in a
 * {@link CompeteProvider}, and this picks the right one and gets out of the way. What is left
 * here is the part that is genuinely the same everywhere: personal files, which are CPIntel's
 * own storage and have nothing to do with the judge beyond the admin's rule about whether a
 * given round may open them.
 *
 * <p>Keeping the router this thin is deliberate. The previous version of this class <em>was</em>
 * the Codeforces implementation, and every DOMjudge feature would have arrived as another
 * branch inside it until the two judges' rules were interleaved beyond separating.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CompeteService {

    private final List<CompeteProvider> providers;
    private final PersonalFileService personalFiles;
    /** Ordinary sessions out of running examinations; examination sessions inside theirs. */
    private final com.cpintel.events.ExamSessionGuard examGuard;
    private final ContestFilePolicy filePolicy;
    private final ProctoringGate proctoring;
    private final LanguagePolicy languagePolicy;
    private final ExamSessionRecorder examSessions;

    private Map<String, CompeteProvider> byPlatform;

    /**
     * Indexed once, on first use.
     *
     * Spring injects the providers as a list; turning it into a map here rather than in a
     * constructor keeps this testable with a hand-built list and costs one branch per call.
     */
    private Map<String, CompeteProvider> providers() {
        if (byPlatform == null) {
            Map<String, CompeteProvider> map = new HashMap<>();
            for (CompeteProvider provider : providers) map.put(provider.platform(), provider);
            byPlatform = map;
        }
        return byPlatform;
    }

    /**
     * The provider for a platform, or a message naming the ones that exist.
     *
     * A platform with no provider behind it is refused here, in one place, rather than by each
     * endpoint discovering it separately.
     */
    public CompeteProvider provider(String platform) {
        String key = platform == null ? "" : platform.trim().toUpperCase(Locale.ROOT);
        CompeteProvider provider = providers().get(key);
        if (provider == null) {
            throw ApiException.badRequest(
                "No contest support for '" + platform + "'. Available: "
                    + String.join(", ", providers().keySet().stream().sorted().toList()));
        }
        return provider;
    }

    public CompeteProvider provider(CompeteDto.Platform platform) {
        if (platform == null) {
            throw ApiException.badRequest("Pick the judge this contest runs on.");
        }
        return provider(platform.name());
    }

    // ------------------------------------------------------------- delegation

    public String parseContestId(CompeteDto.Platform platform, String raw) {
        return provider(platform).parseContestId(raw);
    }

    public CompeteDto.ContestInfo contestInfo(Long userId, String platform, String contestId) {
        examGuard.requireContestAccess(userId, platform, contestId);
        return provider(platform).contestInfo(userId, contestId);
    }

    public PracticeDto.ProblemDetail statement(Long userId, String platform, String contestId,
                                               String index) {
        examGuard.requireContestAccess(userId, platform, contestId);
        return provider(platform).statement(userId, contestId, index);
    }

    public CompeteDto.StatementDocument statementDocument(Long userId, String platform,
                                                          String contestId, String index) {
        examGuard.requireContestAccess(userId, platform, contestId);
        return provider(platform).statementDocument(userId, contestId, index);
    }

    /**
     * The statement as plain text, whatever it was published as.
     *
     * <p>A PDF is shown to the contestant as the PDF, but nothing can read its examples out of
     * the embed, so a problem uploaded as a PDF opened with an empty test case. The text is what
     * the arena's sample parser reads, exactly as it reads a statement published as .txt.
     */
    public String statementText(Long userId, String platform, String contestId, String index) {
        CompeteDto.StatementDocument doc = statementDocument(userId, platform, contestId, index);
        if (!doc.isPdf()) return new String(doc.bytes(), java.nio.charset.StandardCharsets.UTF_8);
        try (var pdf = org.apache.pdfbox.Loader.loadPDF(doc.bytes())) {
            var stripper = new org.apache.pdfbox.text.PDFTextStripper();
            // Keeps columns apart with runs of spaces, so side-by-side examples stay split.
            stripper.setSortByPosition(true);
            return stripper.getText(pdf);
        } catch (java.io.IOException e) {
            throw new com.cpintel.exception.ApiException(
                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "STATEMENT_UNREADABLE",
                "The statement PDF has no readable text.");
        }
    }

    /**
     * The languages this contest will accept, as the editor's picker shows them.
     *
     * <p>The judge's own list, narrowed to what the event's administrator allowed. The
     * narrowing is here rather than in a provider because it is a property of the event and not
     * of the judge — see {@link LanguagePolicy} — so one rule covers both judges and cannot
     * drift between them.
     */
    public List<PracticeDto.LanguageOption> languages(Long userId, String platform,
                                                      String contestId) {
        examGuard.requireContestAccess(userId, platform, contestId);
        CompeteProvider target = provider(platform);
        List<PracticeDto.LanguageOption> offered = target.languages(userId, contestId);

        Set<String> allowed =
            languagePolicy.restrictionFor(userId, target.platform(), contestId);
        return languagePolicy.filter(offered, allowed);
    }

    /**
     * Sends a solution to the judge, once the round's own rules allow it.
     *
     * <p>The proctoring check sits here rather than inside a provider on purpose. It is a
     * property of the <em>round</em> — an admin marked a group contest as monitored — and has
     * nothing to do with which judge the contest happens to run on. Putting it in the DOMjudge
     * provider would have left the identical Codeforces round ungated, and the second copy
     * would have drifted from the first.
     *
     * <p>It runs before the provider is asked for anything, so a refused submission never
     * reaches the judge and never lands in the archive.
     */
    public CompeteDto.ContestSubmission submit(Long userId, String platform, String contestId,
                                               CompeteDto.ContestSubmitRequest req) {
        examGuard.requireContestAccess(userId, platform, contestId);
        CompeteProvider target = provider(platform);
        proctoring.requireMonitored(userId, target.platform(), contestId);

        // Only when an admin actually restricted this event. The check costs a call to the
        // judge's language catalogue, which on Codeforces means scraping a page — so the
        // unrestricted path, which is every contest and most examinations, does not pay for it.
        Set<String> allowed =
            languagePolicy.restrictionFor(userId, target.platform(), contestId);
        if (!allowed.isEmpty()) {
            languagePolicy.requireAllowed(allowed, req.languageId(),
                languagePolicy.filter(target.languages(userId, contestId), allowed));
        }

        CompeteDto.ContestSubmission submission = target.submit(userId, contestId, req);

        // Recorded here rather than left to the client: a submission passes through the server
        // on its way to the judge, so it is one of the few parts of an examination session
        // CPIntel knows first-hand and nobody can decline to report. Does nothing outside an
        // examination, which is the common case.
        examSessions.recordSubmission(userId, target.platform(), contestId, req.index(),
            submission == null ? null : submission.id());

        return submission;
    }

    public List<CompeteDto.ContestSubmission> submissions(Long userId, String platform,
                                                          String contestId) {
        examGuard.requireContestAccess(userId, platform, contestId);
        return provider(platform).submissions(userId, contestId);
    }

    public CompeteDto.RankInfo rank(Long userId, String platform, String contestId) {
        examGuard.requireContestAccess(userId, platform, contestId);
        return provider(platform).rank(userId, contestId);
    }

    public CompeteDto.Leaderboard leaderboard(Long userId, String platform, String contestId) {
        examGuard.requireContestAccess(userId, platform, contestId);
        return provider(platform).leaderboard(userId, contestId);
    }

    // ---------------------------------------------------------- personal files

    /**
     * The user's own files, read through the contest.
     *
     * Routed here rather than straight at the library so the admin's rule for this contest is
     * the thing that answers. The library endpoints stay open either way — a closed contest
     * stops the contest page serving files, it does not take away someone's own storage.
     */
    public FilesDto.Vault files(Long userId, String platform, String contestId) {
        examGuard.requireContestAccess(userId, platform, contestId);
        requireFilesEnabled(platform, contestId);
        return personalFiles.vault(userId);
    }

    public FilesDto.FileContent file(Long userId, String platform, String contestId,
                                     String fileId) {
        examGuard.requireContestAccess(userId, platform, contestId);
        requireFilesEnabled(platform, contestId);
        return personalFiles.content(userId, fileId);
    }

    public FilesDto.Download fileDownload(Long userId, String platform, String contestId,
                                          String fileId) {
        examGuard.requireContestAccess(userId, platform, contestId);
        requireFilesEnabled(platform, contestId);
        return personalFiles.download(userId, fileId);
    }

    private void requireFilesEnabled(String platform, String contestId) {
        // provider() first, so an unknown platform reads as an unsupported judge rather than
        // as a personal-files refusal.
        filePolicy.require(provider(platform).platform(), contestId);
    }
}
