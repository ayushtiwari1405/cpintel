package com.cpintel.compete;

import com.cpintel.archive.SubmissionArchive;
import com.cpintel.files.ContestFilePolicy;
import com.cpintel.integration.codeforces.CfContestRef;
import com.cpintel.integration.codeforces.CfStandingsResponse;
import com.cpintel.integration.codeforces.CodeforcesClient;
import com.cpintel.practice.CfSessionStore;
import com.cpintel.practice.CfStatementScraper;
import com.cpintel.practice.CfWebSubmitClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A session connected through the desktop app or the extension has no cookies, so the server
 * cannot read Codeforces' pages for it. Its contest must still open for submitting while the
 * contest runs, instead of reading as "not registered" off a signed-out page.
 */
class CodeforcesRelayContestInfoTest {

    private static final String GROUP_CONTEST = "wLuDESxnIs~730543";

    private final CfSessionStore sessions = mock(CfSessionStore.class);
    private final CfContestScraper pages = mock(CfContestScraper.class);
    private final CfWebSubmitClient submitClient = mock(CfWebSubmitClient.class);
    private final CfManagerApi manager = mock(CfManagerApi.class);
    private CodeforcesCompeteProvider provider;

    @BeforeEach
    void setUp() {
        provider = new CodeforcesCompeteProvider(mock(CodeforcesClient.class), mock(CfStatementScraper.class),
            submitClient, sessions, pages, mock(SubmissionArchive.class), mock(ContestFilePolicy.class), manager);
        ReflectionTestUtils.setField(provider, "submitEnabled", true);
    }

    private void groupContest(String phase) {
        CfStandingsResponse.Contest contest = new CfStandingsResponse.Contest();
        contest.setName("CP_Intel_Test");
        contest.setPhase(phase);
        contest.setStartTimeSeconds(Instant.now().minusSeconds(60).getEpochSecond());
        contest.setDurationSeconds(3000L);
        CfStandingsResponse.Problem a = new CfStandingsResponse.Problem();
        a.setIndex("a");
        a.setName("Fraction and Six");
        CfStandingsResponse.Result result = new CfStandingsResponse.Result();
        result.setContest(contest);
        result.setProblems(List.of(a));
        when(manager.standings(any(CfContestRef.class))).thenReturn(result);
    }

    @Test
    void relayedSessionCanSubmitWhileTheContestRuns() {
        when(sessions.find(46L)).thenReturn(new CfSessionStore.StoredSession("burner", null, null, null, null));
        groupContest("CODING");

        CompeteDto.ContestInfo info = provider.contestInfo(46L, GROUP_CONTEST);

        assertThat(info.submissionsOpen()).isTrue();
        assertThat(info.submissionsClosedReason()).isNull();
        assertThat(info.problems()).extracting(CompeteDto.ContestProblem::index).containsExactly("A");
        verify(submitClient, never()).canSubmitTo(any(), any(), anyString());
        verify(pages, never()).dashboard(any(), any(CfContestRef.class));
    }

    @Test
    void relayedSessionIsClosedBeforeTheStart() {
        when(sessions.find(46L)).thenReturn(new CfSessionStore.StoredSession("burner", "", null, null, null));
        groupContest("BEFORE");

        CompeteDto.ContestInfo info = provider.contestInfo(46L, GROUP_CONTEST);

        assertThat(info.submissionsOpen()).isFalse();
        assertThat(info.submissionsClosedReason()).isEqualTo("The contest has not started yet.");
    }

    @Test
    void cookieSessionStillAsksCodeforces() {
        when(sessions.find(46L)).thenReturn(new CfSessionStore.StoredSession("burner", "JSESSIONID=x", "ua", null, null));
        groupContest("CODING");
        when(pages.dashboard(anyString(), any(CfContestRef.class)))
            .thenReturn(new CfContestScraper.ContestPage(true, true, 2900L, "CP_Intel_Test", List.of()));
        when(submitClient.canSubmitTo(anyString(), anyString(), anyString())).thenReturn(false);

        CompeteDto.ContestInfo info = provider.contestInfo(46L, GROUP_CONTEST);

        assertThat(info.submissionsOpen()).isFalse();
        assertThat(info.submissionsClosedReason()).contains("registered");
    }
}
