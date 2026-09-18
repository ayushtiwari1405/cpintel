package com.cpintel.compete;

import com.cpintel.archive.SubmissionArchive;
import com.cpintel.entity.ContestGroup;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.GroupMember;
import com.cpintel.entity.User;
import com.cpintel.files.ContestFilePolicy;
import com.cpintel.integration.domjudge.DjModels;
import com.cpintel.integration.domjudge.DomjudgeClient;
import com.cpintel.integration.domjudge.DomjudgeSampleClient;
import com.cpintel.repository.jpa.GroupContestRepository;
import com.cpintel.repository.jpa.GroupMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The DOMjudge arena's two silent-wrongness areas: what phase a contest is in, and what a
 * submission's verdict is.
 *
 * Both are places where a plausible-looking mistake produces a page that renders perfectly and
 * says the wrong thing — a contest that reads as not-started while it is running, or a verdict
 * shown as accepted when the judge rejected it. Neither throws, so neither is caught by
 * anything except a test that asserts the mapping.
 */
class DomjudgeCompeteProviderTest {

    private static final String CID = "nwerc18";
    private static final Long USER = 42L;
    private static final String TEAM = "t7";

    private DomjudgeClient domjudge;
    private DomjudgeContestCache cache;
    private GroupContestRepository contestRepository;
    private GroupMemberRepository memberRepository;
    private DomjudgeCompeteProvider provider;

    @BeforeEach
    void setUp() {
        domjudge = mock(DomjudgeClient.class);
        cache = mock(DomjudgeContestCache.class);
        DomjudgeSampleClient samples = mock(DomjudgeSampleClient.class);
        contestRepository = mock(GroupContestRepository.class);
        memberRepository = mock(GroupMemberRepository.class);
        SubmissionArchive archive = mock(SubmissionArchive.class);
        ContestFilePolicy filePolicy = mock(ContestFilePolicy.class);

        when(domjudge.isConfigured()).thenReturn(true);
        when(domjudge.root()).thenReturn("https://judge.example.edu");
        when(filePolicy.enabledFor(anyString(), anyString())).thenReturn(true);
        when(samples.samples(anyString(), anyString())).thenReturn(List.of());

        provider = new DomjudgeCompeteProvider(domjudge, cache, samples,
            contestRepository, memberRepository, archive, filePolicy);
    }

    /** Puts this user in a group laid over the contest, competing as {@link #TEAM}. */
    private void enrolUser() {
        User user = User.builder().userId(USER).username("ada").build();
        ContestGroup group = ContestGroup.builder().groupId(1L).name("Class").build();
        GroupContest contest = GroupContest.builder()
            .contestId(9L).group(group)
            .platform("DOMJUDGE").externalId(CID)
            .build();
        GroupMember member = GroupMember.builder()
            .group(group).user(user).externalHandle("Team Alpha")
            .build();

        when(contestRepository.findForParticipant(USER, "DOMJUDGE", CID))
            .thenReturn(List.of(contest));
        when(memberRepository.findByGroupGroupIdAndUserUserId(1L, USER))
            .thenReturn(Optional.of(member));
        when(cache.teamIdByName(CID, "Team Alpha")).thenReturn(TEAM);
    }

    private DjModels.Contest contestMeta() {
        DjModels.Contest meta = new DjModels.Contest();
        meta.setId(CID);
        meta.setName("NWERC 2018");
        meta.setStart_time("2026-09-03T09:00:00+00:00");
        meta.setEnd_time("2026-09-03T14:00:00+00:00");
        meta.setDuration("5:00:00.000");
        return meta;
    }

    private DjModels.State state(String started, String ended, String frozen, String thawed) {
        DjModels.State s = new DjModels.State();
        s.setStarted(started);
        s.setEnded(ended);
        s.setFrozen(frozen);
        s.setThawed(thawed);
        return s;
    }

    private DjModels.ContestProblem problem(String id, String label) {
        DjModels.ContestProblem p = new DjModels.ContestProblem();
        p.setId(id);
        p.setLabel(label);
        p.setName("Problem " + label);
        return p;
    }

    private DjModels.Language language(String id, String name) {
        DjModels.Language l = new DjModels.Language();
        l.setId(id);
        l.setName(name);
        l.setExtensions(List.of("cpp"));
        return l;
    }

    // ------------------------------------------------------------------ phase

    @Nested
    @DisplayName("contest phase, read from five nullable timestamps")
    class Phase {

        @BeforeEach
        void common() {
            enrolUser();
            when(cache.contest(CID)).thenReturn(contestMeta());
            when(cache.problems(CID)).thenReturn(List.of(problem("p1", "A")));
            when(cache.languages(CID)).thenReturn(List.of(language("cpp", "C++")));
        }

        @Test
        @DisplayName("no start timestamp means the contest has not begun")
        void before() {
            when(cache.state(CID)).thenReturn(state(null, null, null, null));

            CompeteDto.ContestInfo info = provider.contestInfo(USER, CID);

            assertEquals("BEFORE", info.phase());
            assertFalse(info.running());
            assertFalse(info.submissionsOpen());
            assertNotNull(info.submissionsClosedReason());
        }

        @Test
        @DisplayName("started and not ended is the running case")
        void running() {
            when(cache.state(CID))
                .thenReturn(state("2026-09-03T09:00:00+00:00", null, null, null));

            CompeteDto.ContestInfo info = provider.contestInfo(USER, CID);

            assertEquals("CODING", info.phase());
            assertTrue(info.running());
            assertTrue(info.submissionsOpen(), "an enrolled team may submit while it runs");
        }

        @Test
        @DisplayName("an end timestamp closes it, even though 'started' is still set")
        void finished() {
            when(cache.state(CID)).thenReturn(state(
                "2026-09-03T09:00:00+00:00", "2026-09-03T14:00:00+00:00", null, null));

            CompeteDto.ContestInfo info = provider.contestInfo(USER, CID);

            assertEquals("FINISHED", info.phase());
            assertFalse(info.running());
            assertFalse(info.submissionsOpen());
        }

        @Test
        @DisplayName("frozen only counts while it has not been thawed")
        void freeze() {
            when(cache.state(CID)).thenReturn(state(
                "2026-09-03T09:00:00+00:00", null, "2026-09-03T13:00:00+00:00", null));
            assertTrue(provider.contestInfo(USER, CID).frozen());

            when(cache.state(CID)).thenReturn(state(
                "2026-09-03T09:00:00+00:00", null,
                "2026-09-03T13:00:00+00:00", "2026-09-03T14:30:00+00:00"));
            assertFalse(provider.contestInfo(USER, CID).frozen());
        }

        @Test
        @DisplayName("statements are declared as PDF so the pane knows how to render")
        void statementFormat() {
            when(cache.state(CID))
                .thenReturn(state("2026-09-03T09:00:00+00:00", null, null, null));

            assertEquals(CompeteDto.StatementFormat.PDF,
                provider.contestInfo(USER, CID).statementFormat());
        }

        @Test
        @DisplayName("someone not in a group over this contest is told why they cannot submit")
        void notEnrolled() {
            when(contestRepository.findForParticipant(USER, "DOMJUDGE", CID))
                .thenReturn(List.of());
            when(cache.state(CID))
                .thenReturn(state("2026-09-03T09:00:00+00:00", null, null, null));

            CompeteDto.ContestInfo info = provider.contestInfo(USER, CID);

            assertFalse(info.submissionsOpen());
            assertTrue(info.submissionsClosedReason().toLowerCase().contains("team"),
                "the reason should point at the team mapping, which is what an admin fixes");
        }
    }

    // --------------------------------------------------------------- verdicts

    @Nested
    @DisplayName("verdicts, mapped onto the vocabulary the arena already renders")
    class Verdicts {

        private DjModels.Submission submission(String id, String team, String problemId) {
            DjModels.Submission s = new DjModels.Submission();
            s.setId(id);
            s.setTeam_id(team);
            s.setProblem_id(problemId);
            s.setLanguage_id("cpp");
            s.setTime("2026-09-03T09:30:00+00:00");
            return s;
        }

        private DjModels.Judgement judgement(String submissionId, String type) {
            DjModels.Judgement j = new DjModels.Judgement();
            j.setSubmission_id(submissionId);
            j.setJudgement_type_id(type);
            j.setValid(true);
            return j;
        }

        private DjModels.JudgementType type(String id, boolean solved) {
            DjModels.JudgementType t = new DjModels.JudgementType();
            t.setId(id);
            t.setSolved(solved);
            return t;
        }

        @BeforeEach
        void common() {
            enrolUser();
            when(cache.problems(CID)).thenReturn(List.of(problem("p1", "A")));
            when(cache.judgementTypes(CID)).thenReturn(Map.of(
                "AC", type("AC", true),
                "WA", type("WA", false)));
        }

        @Test
        @DisplayName("an accepted submission reads as OK, which is what marks a problem solved")
        void accepted() {
            when(cache.submissions(CID)).thenReturn(List.of(submission("1", TEAM, "p1")));
            when(cache.judgementsBySubmission(CID))
                .thenReturn(Map.of("1", judgement("1", "AC")));

            List<CompeteDto.ContestSubmission> rows = provider.submissions(USER, CID);

            assertEquals(1, rows.size());
            assertEquals("OK", rows.get(0).verdict());
            assertTrue(rows.get(0).finished());
            assertEquals("A", rows.get(0).index());
        }

        @Test
        @DisplayName("a rejected submission keeps the judge's meaning, not a guess")
        void rejected() {
            when(cache.submissions(CID)).thenReturn(List.of(submission("1", TEAM, "p1")));
            when(cache.judgementsBySubmission(CID))
                .thenReturn(Map.of("1", judgement("1", "WA")));

            assertEquals("WRONG_ANSWER", provider.submissions(USER, CID).get(0).verdict());
        }

        @Test
        @DisplayName("no judgement yet is still judging, not a failure")
        void pending() {
            when(cache.submissions(CID)).thenReturn(List.of(submission("1", TEAM, "p1")));
            when(cache.judgementsBySubmission(CID)).thenReturn(Map.of());

            CompeteDto.ContestSubmission row = provider.submissions(USER, CID).get(0);
            assertEquals("TESTING", row.verdict());
            assertFalse(row.finished(), "a pending row must keep the console polling");
        }

        @Test
        @DisplayName("a judgement type this build does not know is shown as the judge named it")
        void unknownType() {
            when(cache.submissions(CID)).thenReturn(List.of(submission("1", TEAM, "p1")));
            when(cache.judgementsBySubmission(CID))
                .thenReturn(Map.of("1", judgement("1", "CUSTOM_REJECT")));

            assertEquals("CUSTOM_REJECT",
                provider.submissions(USER, CID).get(0).verdict(),
                "inventing a verdict for a custom type would misreport the judge");
        }

        @Test
        @DisplayName("only this team's submissions come back")
        void filtersToOwnTeam() {
            when(cache.submissions(CID)).thenReturn(List.of(
                submission("1", TEAM, "p1"),
                submission("2", "someone-else", "p1")));
            when(cache.judgementsBySubmission(CID)).thenReturn(Map.of());

            List<CompeteDto.ContestSubmission> rows = provider.submissions(USER, CID);

            assertEquals(1, rows.size());
            assertEquals("1", rows.get(0).id());
        }

        @Test
        @DisplayName("a contestant with no team mapping sees an empty list, not an error")
        void noTeam() {
            when(contestRepository.findForParticipant(USER, "DOMJUDGE", CID))
                .thenReturn(List.of());

            assertTrue(provider.submissions(USER, CID).isEmpty());
        }
    }

    // ----------------------------------------------------------------- parsing

    @Nested
    @DisplayName("contest ids, which DOMjudge does not require to be numbers")
    class Parsing {

        @Test
        @DisplayName("a bare non-numeric id is valid")
        void bareId() {
            assertEquals("nwerc18", provider.parseContestId("nwerc18"));
        }

        @Test
        @DisplayName("an id is lifted out of a link")
        void fromUrl() {
            assertEquals("3", provider.parseContestId("https://judge.example.edu/contests/3"));
        }

        @Test
        @DisplayName("surrounding whitespace is forgiven")
        void trimmed() {
            assertEquals("nwerc18", provider.parseContestId("  nwerc18 "));
        }
    }
}
