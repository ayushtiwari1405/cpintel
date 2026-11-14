package com.cpintel.service;

import com.cpintel.entity.PlatformAccount;
import com.cpintel.exception.ApiException;
import com.cpintel.integration.codeforces.CfModels;
import com.cpintel.integration.codeforces.CfSubmissionsResponse;
import com.cpintel.integration.codeforces.CodeforcesClient;
import com.cpintel.integration.leetcode.LeetCodeClient;
import com.cpintel.mapper.UserMapper;
import com.cpintel.repository.jpa.PlatformAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HandleVerificationServiceTest {

    private PlatformAccountRepository accounts;
    private CodeforcesClient codeforces;
    private LeetCodeClient leetCode;
    private HandleVerificationService service;

    @BeforeEach
    void setUp() {
        accounts = mock(PlatformAccountRepository.class);
        codeforces = mock(CodeforcesClient.class);
        leetCode = mock(LeetCodeClient.class);
        when(accounts.save(any())).thenAnswer(c -> c.getArgument(0));
        service = new HandleVerificationService(accounts, codeforces, leetCode, new UserMapper());
    }

    private PlatformAccount linked(String platform) {
        PlatformAccount a = PlatformAccount.builder().accountId(1L).platform(platform).handle("alice").build();
        when(accounts.findByUserUserIdAndPlatform(7L, platform)).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    void leetCodeIsProvenByTheCodeInTheProfile() {
        PlatformAccount a = linked("LEETCODE");
        var challenge = service.start(7L, "LEETCODE");
        assertThat(challenge.code()).startsWith("cpintel-");

        when(leetCode.profile("alice")).thenReturn(new LeetCodeClient.Profile("alice", "hi"));
        assertThatThrownBy(() -> service.check(7L, "LEETCODE")).isInstanceOf(ApiException.class);
        assertThat(a.getVerifiedAt()).isNull();

        when(leetCode.profile("alice")).thenReturn(
            new LeetCodeClient.Profile("alice", "student " + challenge.code()));
        assertThat(service.check(7L, "LEETCODE").getVerifiedAt()).isNotNull();
        assertThat(a.getVerifyCode()).isNull();
    }

    @Test
    void codeforcesNeedsACompilationErrorOnTheNamedProblemAfterTheProofOpened() {
        PlatformAccount a = linked("CODEFORCES");
        service.start(7L, "CODEFORCES");
        Instant opened = a.getVerifyIssuedAt();

        CfSubmissionsResponse resp = new CfSubmissionsResponse();
        resp.setResult(List.of(
            submission(4, "A", "OK", opened.plusSeconds(30)),
            submission(4, "A", "COMPILATION_ERROR", opened.minus(Duration.ofHours(1))),
            submission(5, "A", "COMPILATION_ERROR", opened.plusSeconds(30))));
        when(codeforces.getSubmissions("alice", 1, 20)).thenReturn(resp);
        assertThatThrownBy(() -> service.check(7L, "CODEFORCES")).isInstanceOf(ApiException.class);

        resp.setResult(List.of(submission(4, "A", "COMPILATION_ERROR", opened.plusSeconds(30))));
        assertThat(service.check(7L, "CODEFORCES").getVerifiedAt()).isNotNull();
    }

    @Test
    void anExpiredProofIsRefused() {
        PlatformAccount a = linked("LEETCODE");
        a.setVerifyCode("cpintel-OLD");
        a.setVerifyIssuedAt(Instant.now().minus(Duration.ofHours(1)));
        assertThatThrownBy(() -> service.check(7L, "LEETCODE"))
            .isInstanceOf(ApiException.class).hasMessageContaining("expired");
        // Starting again hands out a fresh code.
        assertThat(service.start(7L, "LEETCODE").code()).isNotEqualTo("cpintel-OLD");
    }

    private static CfModels.Submission submission(int contest, String index, String verdict, Instant at) {
        CfModels.Submission s = new CfModels.Submission();
        CfModels.Submission.Problem p = new CfModels.Submission.Problem();
        p.setContestId(contest);
        p.setIndex(index);
        s.setProblem(p);
        s.setVerdict(verdict);
        s.setCreationTimeSeconds(at.getEpochSecond());
        return s;
    }
}
