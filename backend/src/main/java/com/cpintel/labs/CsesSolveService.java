package com.cpintel.labs;

import com.cpintel.entity.LabProblem;
import com.cpintel.exception.ApiException;
import com.cpintel.integration.cses.CsesClient;
import com.cpintel.repository.jpa.PlatformAccountRepository;
import com.cpintel.service.CsesAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Submitting a lab's CSES task from CPIntel, with the student's own CSES sign-in.
 *
 * <p>The server sends it (CSES's API takes a token, not a browser), and reads the verdict back
 * the same way. An accepted submission to the task is recorded for the lab straight away.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CsesSolveService {

    /** One submission per student per this long: CSES's judge is shared by everyone. */
    static final Duration SUBMIT_EVERY = Duration.ofSeconds(10);

    /** CPIntel's runner languages, as CSES names them, and the file name each is sent as. */
    record CsesLanguage(String name, String option, String filename) {}

    static final Map<String, CsesLanguage> LANGUAGES = Map.of(
        "cpp", new CsesLanguage("C++", "C++17", "main.cpp"),
        "c", new CsesLanguage("C", null, "main.c"),
        "java", new CsesLanguage("Java", null, "Main.java"),
        "python3", new CsesLanguage("Python3", null, "main.py"));

    private final LabService labs;
    private final LabCheckService checker;
    private final CsesClient cses;
    private final CsesAccountService csesAccounts;
    private final PlatformAccountRepository accounts;
    private final RedisTemplate<String, Object> redis;

    // No transaction here: the lookups have their own, and recording a solve needs a writable one.
    public LabsDto.CsesVerdict submit(Long userId, Long labProblemId, LabsDto.CsesSubmitRequest req) {
        LabProblem p = labs.studentProblem(userId, labProblemId, ProblemRef.CSES);
        CsesLanguage lang = LANGUAGES.get(req.language());
        if (lang == null) throw ApiException.badRequest("CSES submissions here can be C++, C, Java or Python 3.");
        String token = csesAccounts.tokenOf(userId);
        if (!claim(userId)) throw ApiException.tooManyRequests("Wait a few seconds between submissions.");
        try {
            return verdict(cses.submit(token, p.getProblemKey(), new CsesClient.Language(lang.name(), lang.option()),
                lang.filename(), req.code()));
        } catch (CsesClient.TokenRejected e) {
            accounts.findByUserUserIdAndPlatform(userId, "CSES").ifPresent(csesAccounts::markRejected);
            throw ApiException.badRequest("CSES no longer accepts your sign-in. Reconnect CSES on the Platforms page.");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("CSES submit failed: {}", e.getMessage());
            throw ApiException.badRequest(e.getMessage() == null ? "CSES refused the submission." : e.getMessage());
        }
    }

    public LabsDto.CsesVerdict verdict(Long userId, Long labProblemId, long submissionId) {
        LabProblem p = labs.studentProblem(userId, labProblemId, ProblemRef.CSES);
        String token = csesAccounts.tokenOf(userId);
        CsesClient.Graded g;
        try {
            g = cses.submission(token, submissionId);
        } catch (CsesClient.TokenRejected e) {
            throw ApiException.badRequest("CSES no longer accepts your sign-in. Reconnect CSES on the Platforms page.");
        } catch (Exception e) {
            throw ApiException.serviceUnavailable("CSES did not answer. Try again in a moment.");
        }
        // Only a submission to this very task credits it.
        if (!g.pending() && "ACCEPTED".equalsIgnoreCase(g.result()) && p.getProblemKey().equals(g.taskId())) {
            String handle = accounts.findByUserUserIdAndPlatform(userId, "CSES").map(a -> a.getHandle()).orElse("");
            checker.recordWatched(userId, p.getLab(), p, handle, g.time() == null ? Instant.now() : g.time(),
                String.valueOf(g.id()));
        }
        return verdict(g);
    }

    private static LabsDto.CsesVerdict verdict(CsesClient.Graded g) {
        return new LabsDto.CsesVerdict(g.id(), g.pending(), g.status(), g.result(), g.finishedTests(),
            g.totalTests(), g.compiler(),
            g.tests().stream().map(t -> new LabsDto.CsesTest(t.number(), t.verdict(), t.timeMs())).toList());
    }

    private boolean claim(Long userId) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("cses-submit:" + userId, "1", SUBMIT_EVERY));
        } catch (Exception e) {
            return true;
        }
    }
}
