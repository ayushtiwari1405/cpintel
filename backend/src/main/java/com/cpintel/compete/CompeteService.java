package com.cpintel.compete;

import com.cpintel.exception.ApiException;
import com.cpintel.files.ContestFilePolicy;
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
    private final ContestFilePolicy filePolicy;

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
     * CodeChef is a declared platform with no provider behind it, so this is the single place
     * that says so — rather than each endpoint discovering it separately.
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
        return provider(platform).contestInfo(userId, contestId);
    }

    public PracticeDto.ProblemDetail statement(Long userId, String platform, String contestId,
                                               String index) {
        return provider(platform).statement(userId, contestId, index);
    }

    public byte[] statementPdf(Long userId, String platform, String contestId, String index) {
        return provider(platform).statementPdf(userId, contestId, index);
    }

    public List<PracticeDto.LanguageOption> languages(Long userId, String platform,
                                                      String contestId) {
        return provider(platform).languages(userId, contestId);
    }

    public CompeteDto.ContestSubmission submit(Long userId, String platform, String contestId,
                                               CompeteDto.ContestSubmitRequest req) {
        return provider(platform).submit(userId, contestId, req);
    }

    public List<CompeteDto.ContestSubmission> submissions(Long userId, String platform,
                                                          String contestId) {
        return provider(platform).submissions(userId, contestId);
    }

    public CompeteDto.RankInfo rank(Long userId, String platform, String contestId) {
        return provider(platform).rank(userId, contestId);
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
        requireFilesEnabled(platform, contestId);
        return personalFiles.vault(userId);
    }

    public FilesDto.FileContent file(Long userId, String platform, String contestId,
                                     String fileId) {
        requireFilesEnabled(platform, contestId);
        return personalFiles.content(userId, fileId);
    }

    public FilesDto.Download fileDownload(Long userId, String platform, String contestId,
                                          String fileId) {
        requireFilesEnabled(platform, contestId);
        return personalFiles.download(userId, fileId);
    }

    private void requireFilesEnabled(String platform, String contestId) {
        // provider() first, so an unknown platform reads as an unsupported judge rather than
        // as a personal-files refusal.
        filePolicy.require(provider(platform).platform(), contestId);
    }
}
