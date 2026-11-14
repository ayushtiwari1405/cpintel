package com.cpintel.service;

import com.cpintel.dto.PlatformDto;
import com.cpintel.entity.PlatformAccount;
import com.cpintel.exception.ApiException;
import com.cpintel.integration.codeforces.CfModels;
import com.cpintel.integration.codeforces.CodeforcesClient;
import com.cpintel.integration.leetcode.LeetCodeClient;
import com.cpintel.mapper.UserMapper;
import com.cpintel.repository.jpa.PlatformAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Proving a linked handle is the student's own, before a lab credits anything it solved.
 *
 * <p>Linking only checks that a handle exists, so anyone could link a stronger friend's. A
 * proof needs something only the account's owner can do:
 * <ul>
 *   <li><b>Codeforces</b>: send a submission that fails to compile to a named problem, within
 *       the window. It is the check Codeforces' own community bots use; a compilation error
 *       costs nothing and never touches a rating.</li>
 *   <li><b>LeetCode</b>: put the code in the profile's summary ("About me"), which LeetCode
 *       shows to anyone. The student can take it out again once verified.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HandleVerificationService {

    /** How long a proof stays open. */
    static final Duration WINDOW = Duration.ofMinutes(20);
    /** Slack for the platform's clock running behind ours. */
    static final Duration SKEW = Duration.ofMinutes(2);

    /** The problem a Codeforces proof is sent to: Watermelon, the one everybody can find. */
    public static final int CF_CONTEST = 4;
    public static final String CF_INDEX = "A";

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PlatformAccountRepository accounts;
    private final CodeforcesClient codeforces;
    private final LeetCodeClient leetCode;
    private final UserMapper mapper;

    public record Challenge(String platform, String handle, String code, Instant issuedAt,
                            Instant expiresAt, String problemUrl) {}

    /** Opens a proof, or hands back the one already open. */
    @Transactional
    public Challenge start(Long userId, String platform) {
        PlatformAccount account = linked(userId, platform);
        if (account.getVerifiedAt() != null) {
            throw ApiException.conflict("Your " + name(platform) + " handle is already verified.");
        }
        Instant now = Instant.now();
        if (account.getVerifyCode() == null || account.getVerifyIssuedAt() == null
                || account.getVerifyIssuedAt().plus(WINDOW).isBefore(now)) {
            account.setVerifyCode("cpintel-" + randomCode(8));
            account.setVerifyIssuedAt(now);
            accounts.save(account);
        }
        return challenge(account);
    }

    /** Looks for the proof on the platform; verifies the handle when it is there. */
    @Transactional
    public PlatformDto.Summary check(Long userId, String platform) {
        PlatformAccount account = linked(userId, platform);
        if (account.getVerifiedAt() != null) return mapper.toPlatformSummary(account);
        Instant now = Instant.now();
        if (account.getVerifyIssuedAt() == null
                || account.getVerifyIssuedAt().plus(WINDOW).isBefore(now)) {
            throw ApiException.badRequest("That proof has expired. Start a new one.");
        }

        boolean proven;
        try {
            proven = switch (platform) {
                case "CODEFORCES" -> provenOnCodeforces(account);
                case "LEETCODE" -> provenOnLeetCode(account);
                default -> throw ApiException.badRequest("Unknown platform: " + platform);
            };
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Verifying {} handle {} failed: {}", platform, account.getHandle(), e.getMessage());
            throw ApiException.serviceUnavailable(name(platform) + " did not answer. Try again in a minute.");
        }
        if (!proven) {
            throw ApiException.badRequest("CPIntel could not find the proof on " + name(platform)
                + " yet. " + ("CODEFORCES".equals(platform)
                    ? "Codeforces can take a minute to list a new submission; try again shortly."
                    : "Save your profile on LeetCode, then try again."));
        }

        account.setVerifiedAt(now);
        account.setVerifyCode(null);
        account.setVerifyIssuedAt(null);
        accounts.save(account);
        log.info("Verified {} handle '{}' for user {}", platform, account.getHandle(), userId);
        return mapper.toPlatformSummary(account);
    }

    private boolean provenOnCodeforces(PlatformAccount account) {
        var resp = codeforces.getSubmissions(account.getHandle(), 1, 20);
        List<CfModels.Submission> recent = resp == null || resp.getResult() == null
            ? List.of() : resp.getResult();
        Instant since = account.getVerifyIssuedAt().minus(SKEW);
        return recent.stream().anyMatch(s -> isProof(s, since));
    }

    static boolean isProof(CfModels.Submission s, Instant since) {
        return s.getProblem() != null
            && Integer.valueOf(CF_CONTEST).equals(s.getProblem().getContestId())
            && CF_INDEX.equals(s.getProblem().getIndex())
            && "COMPILATION_ERROR".equals(s.getVerdict())
            && s.getCreationTimeSeconds() != null
            && !Instant.ofEpochSecond(s.getCreationTimeSeconds()).isBefore(since);
    }

    private boolean provenOnLeetCode(PlatformAccount account) {
        LeetCodeClient.Profile profile = leetCode.profile(account.getHandle());
        return profile != null && profile.aboutMe() != null
            && profile.aboutMe().contains(account.getVerifyCode());
    }

    private PlatformAccount linked(Long userId, String platform) {
        return accounts.findByUserUserIdAndPlatform(userId, platform)
            .orElseThrow(() -> ApiException.notFound("Link your " + name(platform) + " handle first."));
    }

    private Challenge challenge(PlatformAccount a) {
        String url = "CODEFORCES".equals(a.getPlatform())
            ? "https://codeforces.com/problemset/problem/" + CF_CONTEST + "/" + CF_INDEX
            : "https://leetcode.com/profile/";
        return new Challenge(a.getPlatform(), a.getHandle(), a.getVerifyCode(),
            a.getVerifyIssuedAt(), a.getVerifyIssuedAt().plus(WINDOW), url);
    }

    static String name(String platform) {
        return "LEETCODE".equals(platform) ? "LeetCode" : "Codeforces";
    }

    private static String randomCode(int length) {
        StringBuilder b = new StringBuilder(length);
        for (int i = 0; i < length; i++) b.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return b.toString();
    }
}
