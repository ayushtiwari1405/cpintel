package com.cpintel.labs;

import com.cpintel.entity.LabAssignment;
import com.cpintel.entity.LabProblem;
import com.cpintel.entity.LabSolve;
import com.cpintel.entity.PlatformAccount;
import com.cpintel.entity.User;
import com.cpintel.integration.codeforces.CfModels;
import com.cpintel.integration.codeforces.CfSubmissionsResponse;
import com.cpintel.integration.codeforces.CodeforcesClient;
import com.cpintel.integration.leetcode.LeetCodeClient;
import com.cpintel.repository.jpa.ClassroomMemberRepository;
import com.cpintel.repository.jpa.LabAssignmentRepository;
import com.cpintel.repository.jpa.LabSolveRepository;
import com.cpintel.repository.jpa.PlatformAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Who is read, how far back, and what is recorded. */
class LabCheckServiceTest {

    private static final Instant OPENS = Instant.parse("2026-11-10T09:00:00Z");
    private static final Instant DUE = Instant.parse("2026-11-17T09:00:00Z");

    private LabSolveRepository solves;
    private PlatformAccountRepository accounts;
    private LabAudience audience;
    private CodeforcesClient codeforces;
    private LeetCodeClient leetCode;
    private LabCheckService service;
    private com.cpintel.integration.cses.CsesClient cses;
    private com.cpintel.service.CsesAccountService csesAccounts;
    private LabAssignment lab;
    private User alice, bob;

    @BeforeEach
    void setUp() {
        solves = mock(LabSolveRepository.class);
        accounts = mock(PlatformAccountRepository.class);
        audience = mock(LabAudience.class);
        codeforces = mock(CodeforcesClient.class);
        leetCode = mock(LeetCodeClient.class);
        cses = mock(com.cpintel.integration.cses.CsesClient.class);
        csesAccounts = mock(com.cpintel.service.CsesAccountService.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(solves.record(anyLong(), anyLong(), any(), anyBoolean(), anyString(), any())).thenReturn(1);
        service = new LabCheckService(mock(LabAssignmentRepository.class), solves, accounts,
            mock(ClassroomMemberRepository.class), audience, codeforces, leetCode,
            cses, csesAccounts, tx);

        lab = LabAssignment.builder().labId(1L).classroomId(10L).title("Lab 1")
            .opensAt(OPENS).dueAt(DUE).countEarlierSolves(false).problems(new ArrayList<>()).build();
        lab.getProblems().add(problem(100L, "CODEFORCES", "1700/A"));
        lab.getProblems().add(problem(101L, "LEETCODE", "two-sum"));

        alice = User.builder().userId(1L).username("alice").build();
        bob = User.builder().userId(2L).username("bob").build();
        when(audience.students(lab)).thenReturn(List.of(alice, bob));
    }

    @Test
    void readsOnlyVerifiedHandlesAndRecordsWhatTheyFind() {
        when(accounts.findByUserUserIdIn(any())).thenReturn(List.of(
            account(alice, "CODEFORCES", "alice_cf", true),
            account(alice, "LEETCODE", "alice_lc", true),
            account(bob, "CODEFORCES", "bob_cf", false)));
        when(codeforces.getSubmissions("alice_cf", 1, LabCheckService.CF_PAGE))
            .thenReturn(cf(sub(1700, "A", "OK", "2026-11-12T10:00:00Z", 55L)));
        when(leetCode.recentAccepted("alice_lc")).thenReturn(List.of(
            new LeetCodeClient.AcceptedSubmission("9", "two-sum", Instant.parse("2026-11-18T10:00:00Z"))));

        assertThat(service.run(List.of(lab), null)).isEqualTo(2);

        verify(solves).record(100L, 1L, Instant.parse("2026-11-12T10:00:00Z"), false, "alice_cf", "55");
        verify(solves).record(101L, 1L, Instant.parse("2026-11-18T10:00:00Z"), true, "alice_lc", "9");
        // Bob's handle is unproven: never read.
        verify(codeforces, never()).getSubmissions(eq("bob_cf"), anyInt(), anyInt());
    }

    @Test
    void skipsWhatIsAlreadySolvedInTime() {
        when(solves.findByLabProblemIdIn(any())).thenReturn(List.of(
            LabSolve.builder().labProblemId(100L).userId(1L).late(false).build()));
        when(accounts.findByUserUserIdIn(any())).thenReturn(List.of(
            account(alice, "CODEFORCES", "alice_cf", true)));

        service.run(List.of(lab), null);

        verifyNoInteractions(codeforces);
    }

    @Test
    void stopsPagingOnceSubmissionsPredateTheLab() {
        when(accounts.findByUserUserIdIn(any())).thenReturn(List.of(
            account(alice, "CODEFORCES", "alice_cf", true)));
        List<CfModels.Submission> page = new ArrayList<>();
        for (int i = 0; i < LabCheckService.CF_PAGE - 1; i++) page.add(sub(1, "A", "WRONG_ANSWER", "2026-11-11T00:00:00Z", (long) i));
        page.add(sub(1700, "A", "OK", "2026-11-01T00:00:00Z", 999L));
        CfSubmissionsResponse resp = new CfSubmissionsResponse();
        resp.setResult(page);
        when(codeforces.getSubmissions("alice_cf", 1, LabCheckService.CF_PAGE)).thenReturn(resp);

        service.run(List.of(lab), null);

        verify(codeforces, times(1)).getSubmissions(anyString(), anyInt(), anyInt());
        // The OK was before the lab opened, and this lab does not count earlier solves.
        verify(solves, never()).record(anyLong(), anyLong(), any(), anyBoolean(), anyString(), any());
    }

    @Test
    void readsCsesThroughTheStudentsTokenAndOnlyForPassedTasks() {
        lab.getProblems().add(problem(102L, "CSES", "1640"));
        lab.getProblems().add(problem(103L, "CSES", "1068"));
        PlatformAccount account = account(alice, "CSES", "alice_cses", true);
        account.setAccessToken("sealed");
        account.setSyncStatus("COMPLETED");
        when(accounts.findByUserUserIdIn(any())).thenReturn(List.of(account));
        when(csesAccounts.token(account)).thenReturn("tok");
        when(cses.statuses("tok")).thenReturn(java.util.Map.of("1640", "pass", "1068", "fail"));
        when(cses.submissions("tok", "1640")).thenReturn(List.of(
            new com.cpintel.integration.cses.CsesClient.Submission(7, Instant.parse("2026-11-11T08:00:00Z"), "fail"),
            new com.cpintel.integration.cses.CsesClient.Submission(8, Instant.parse("2026-11-11T09:00:00Z"), "pass")));

        service.run(List.of(lab), null);

        verify(solves).record(102L, 1L, Instant.parse("2026-11-11T09:00:00Z"), false, "alice_cses", "8");
        verify(cses, never()).submissions("tok", "1068");
    }

    @Test
    void aRefusedCsesSignInIsMarkedForReconnecting() {
        lab.getProblems().add(problem(102L, "CSES", "1640"));
        PlatformAccount account = account(alice, "CSES", "alice_cses", true);
        account.setAccessToken("sealed");
        when(accounts.findByUserUserIdIn(any())).thenReturn(List.of(account));
        when(csesAccounts.token(account)).thenReturn("tok");
        when(cses.statuses("tok")).thenThrow(new com.cpintel.integration.cses.CsesClient.TokenRejected());

        service.run(List.of(lab), null);

        verify(csesAccounts).markRejected(account);
        verify(solves, never()).record(eq(102L), anyLong(), any(), anyBoolean(), anyString(), any());
    }

    private static LabProblem problem(Long id, String platform, String key) {
        return LabProblem.builder().labProblemId(id).platform(platform).problemKey(key)
            .title(key).url("u").marks(BigDecimal.ONE).ordering(0).build();
    }

    private static PlatformAccount account(User u, String platform, String handle, boolean verified) {
        return PlatformAccount.builder().user(u).platform(platform).handle(handle)
            .verifiedAt(verified ? Instant.now() : null).build();
    }

    private static CfSubmissionsResponse cf(CfModels.Submission... subs) {
        CfSubmissionsResponse r = new CfSubmissionsResponse();
        r.setResult(List.of(subs));
        return r;
    }

    private static CfModels.Submission sub(int contest, String index, String verdict, String at, Long id) {
        CfModels.Submission s = new CfModels.Submission();
        CfModels.Submission.Problem p = new CfModels.Submission.Problem();
        p.setContestId(contest);
        p.setIndex(index);
        s.setProblem(p);
        s.setId(id);
        s.setVerdict(verdict);
        s.setCreationTimeSeconds(Instant.parse(at).getEpochSecond());
        return s;
    }
}
