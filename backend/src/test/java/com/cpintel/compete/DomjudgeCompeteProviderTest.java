package com.cpintel.compete;

import com.cpintel.archive.SubmissionArchive;
import com.cpintel.files.ContestFilePolicy;
import com.cpintel.integration.domjudge.DjModels;
import com.cpintel.integration.domjudge.DomjudgeClient;
import com.cpintel.integration.domjudge.DomjudgeCredentialStore;
import com.cpintel.integration.domjudge.DomjudgeSampleClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The DOMjudge arena's silent-wrongness areas: what phase a contest is in, what a submission's
 * verdict is, and which credentials a read is made with.
 *
 * The first two are places where a plausible-looking mistake produces a page that renders
 * perfectly and says the wrong thing — a contest that reads as not-started while it is running,
 * or a verdict shown as accepted when the judge rejected it. Neither throws, so neither is
 * caught by anything except a test that asserts the mapping.
 *
 * The third is worse, because it is not merely wrong but discloses: reads made as one
 * contestant and cached under a key another contestant shares would show people each other's
 * submissions during a live round. {@link Scope} exists to pin that down.
 */
class DomjudgeCompeteProviderTest {

    private static final String CID = "nwerc18";
    private static final Long USER = 42L;
    private static final String TEAM = "t7";

    /** The DOMjudge login an admin attached to {@link #USER}. */
    private static final DomjudgeCredentialStore.Stored CREDS =
        new DomjudgeCredentialStore.Stored(
            "ada", "pw", "Ada Lovelace", TEAM, "Team Alpha", null, null, Instant.EPOCH);

    private DomjudgeClient domjudge;
    private DomjudgeContestCache cache;
    private DomjudgeCredentialStore credentials;
    private SubmissionArchive archive;
    private DomjudgeCompeteProvider provider;

    @BeforeEach
    void setUp() {
        domjudge = mock(DomjudgeClient.class);
        cache = mock(DomjudgeContestCache.class);
        DomjudgeSampleClient samples = mock(DomjudgeSampleClient.class);
        credentials = mock(DomjudgeCredentialStore.class);
        archive = mock(SubmissionArchive.class);
        ContestFilePolicy filePolicy = mock(ContestFilePolicy.class);

        when(domjudge.isConfigured()).thenReturn(true);
        when(domjudge.root()).thenReturn("https://judge.example.edu");
        when(filePolicy.enabledFor(anyString(), anyString())).thenReturn(true);
        when(samples.samples(any(), anyString(), anyString())).thenReturn(List.of());

        // No service account, which is the deployment shape these tests describe: every read
        // is made as the contestant, so the cache is addressed with their credentials.
        when(domjudge.hasServiceAccount()).thenReturn(false);

        provider = new DomjudgeCompeteProvider(domjudge, cache, samples,
            credentials, archive, filePolicy);
    }

    /** Gives this user an attached DOMjudge account competing as {@link #TEAM}. */
    private void attachAccount() {
        when(credentials.find(USER)).thenReturn(CREDS);
        when(credentials.require(USER)).thenReturn(CREDS);
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
            attachAccount();
            when(cache.contest(CREDS, CID)).thenReturn(contestMeta());
            when(cache.problems(CREDS, CID)).thenReturn(List.of(problem("p1", "A")));
            when(cache.languages(CREDS, CID)).thenReturn(List.of(language("cpp", "C++")));
        }

        @Test
        @DisplayName("no start timestamp means the contest has not begun")
        void before() {
            when(cache.state(CREDS, CID)).thenReturn(state(null, null, null, null));

            CompeteDto.ContestInfo info = provider.contestInfo(USER, CID);

            assertEquals("BEFORE", info.phase());
            assertFalse(info.running());
            assertFalse(info.submissionsOpen());
            assertNotNull(info.submissionsClosedReason());
        }

        @Test
        @DisplayName("started and not ended is the running case")
        void running() {
            when(cache.state(CREDS, CID))
                .thenReturn(state("2026-09-03T09:00:00+00:00", null, null, null));

            CompeteDto.ContestInfo info = provider.contestInfo(USER, CID);

            assertEquals("CODING", info.phase());
            assertTrue(info.running());
            assertTrue(info.submissionsOpen(),
                "a contestant with an attached account may submit while it runs");
        }

        @Test
        @DisplayName("an end timestamp closes it, even though 'started' is still set")
        void finished() {
            when(cache.state(CREDS, CID)).thenReturn(state(
                "2026-09-03T09:00:00+00:00", "2026-09-03T14:00:00+00:00", null, null));

            CompeteDto.ContestInfo info = provider.contestInfo(USER, CID);

            assertEquals("FINISHED", info.phase());
            assertFalse(info.running());
            assertFalse(info.submissionsOpen());
        }

        @Test
        @DisplayName("frozen only counts while it has not been thawed")
        void freeze() {
            when(cache.state(CREDS, CID)).thenReturn(state(
                "2026-09-03T09:00:00+00:00", null, "2026-09-03T13:00:00+00:00", null));
            assertTrue(provider.contestInfo(USER, CID).frozen());

            when(cache.state(CREDS, CID)).thenReturn(state(
                "2026-09-03T09:00:00+00:00", null,
                "2026-09-03T13:00:00+00:00", "2026-09-03T14:30:00+00:00"));
            assertFalse(provider.contestInfo(USER, CID).frozen());
        }

        @Test
        @DisplayName("statements are declared as PDF so the pane knows how to render")
        void statementFormat() {
            when(cache.state(CREDS, CID))
                .thenReturn(state("2026-09-03T09:00:00+00:00", null, null, null));

            assertEquals(CompeteDto.StatementFormat.PDF,
                provider.contestInfo(USER, CID).statementFormat());
        }

        @Test
        @DisplayName("with no attached account and no service account, the contest cannot open")
        void noAccountAtAll() {
            when(credentials.find(USER)).thenReturn(null);

            // Nothing to read the contest as. Refusing here beats a 403 from the judge, which
            // the page would otherwise render as "this contest does not exist".
            assertThrows(RuntimeException.class, () -> provider.contestInfo(USER, CID));
        }

        @Test
        @DisplayName("a contestant with no attached account is told who can fix it")
        void notProvisioned() {
            // A service account can still read the contest, so the page loads — but there is
            // nothing to submit as, and the reason has to name the person who resolves that.
            when(domjudge.hasServiceAccount()).thenReturn(true);
            when(credentials.find(USER)).thenReturn(null);
            when(cache.contest(null, CID)).thenReturn(contestMeta());
            when(cache.problems(null, CID)).thenReturn(List.of(problem("p1", "A")));
            when(cache.languages(null, CID)).thenReturn(List.of(language("cpp", "C++")));
            when(cache.state(null, CID))
                .thenReturn(state("2026-09-03T09:00:00+00:00", null, null, null));

            CompeteDto.ContestInfo info = provider.contestInfo(USER, CID);

            assertFalse(info.submissionsOpen());
            assertTrue(info.submissionsClosedReason().toLowerCase().contains("admin"),
                "the reason should point at the admin, who is the one who attaches an account");
        }
    }

    // ------------------------------------------------------------------ scope

    @Nested
    @DisplayName("which credentials a read is made with")
    class Scope {

        @BeforeEach
        void common() {
            attachAccount();
        }

        @Test
        @DisplayName("without a service account, reads are made as the contestant")
        void perContestant() {
            when(domjudge.hasServiceAccount()).thenReturn(false);
            when(cache.contest(CREDS, CID)).thenReturn(contestMeta());
            when(cache.problems(CREDS, CID)).thenReturn(List.of(problem("p1", "A")));
            when(cache.languages(CREDS, CID)).thenReturn(List.of(language("cpp", "C++")));
            when(cache.state(CREDS, CID)).thenReturn(state(null, null, null, null));

            provider.contestInfo(USER, CID);

            // The credential argument is what keeps one team's cached submissions away from
            // another's. A null here would be a disclosure bug, not a performance choice.
            verify(cache).contest(CREDS, CID);
            verify(cache, never()).contest(isNull(), anyString());
        }

        @Test
        @DisplayName("with a service account, reads are shared so one fetch serves the room")
        void sharedWhenServiceAccountExists() {
            when(domjudge.hasServiceAccount()).thenReturn(true);
            when(cache.contest(null, CID)).thenReturn(contestMeta());
            when(cache.problems(null, CID)).thenReturn(List.of(problem("p1", "A")));
            when(cache.languages(null, CID)).thenReturn(List.of(language("cpp", "C++")));
            when(cache.state(null, CID)).thenReturn(state(null, null, null, null));

            provider.contestInfo(USER, CID);

            verify(cache).contest(null, CID);
        }

        @Test
        @DisplayName("a submission always goes as the contestant, never as the service account")
        void submitAlwaysAsContestant() {
            // The one operation that must not follow the read scope: submitting as the service
            // account would attribute the contestant's work to the service account's team.
            when(domjudge.hasServiceAccount()).thenReturn(true);
            when(cache.problems(null, CID)).thenReturn(List.of(problem("p1", "A")));
            when(cache.languages(null, CID)).thenReturn(List.of(language("cpp", "C++")));
            when(archive.recordAttempt(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("arch-1");
            when(domjudge.submitAs(eq(CREDS), eq(CID), eq("p1"), eq("cpp"), anyString(),
                anyString())).thenReturn("s99");

            CompeteDto.ContestSubmission row = provider.submit(USER, CID,
                new CompeteDto.ContestSubmitRequest("A", "cpp", "int main(){}"));

            assertEquals("s99", row.id());
            verify(domjudge).submitAs(eq(CREDS), eq(CID), eq("p1"), eq("cpp"), anyString(),
                anyString());
        }

        @Test
        @DisplayName("a rejected submission is still archived, and marked as rejected")
        void rejectedIsArchived() {
            when(cache.problems(CREDS, CID)).thenReturn(List.of(problem("p1", "A")));
            when(cache.languages(CREDS, CID)).thenReturn(List.of(language("cpp", "C++")));
            when(archive.recordAttempt(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("arch-1");
            when(domjudge.submitAs(any(), any(), any(), any(), any(), any()))
                .thenThrow(com.cpintel.exception.ApiException.badRequest("refused"));

            assertThrows(RuntimeException.class, () -> provider.submit(USER, CID,
                new CompeteDto.ContestSubmitRequest("A", "cpp", "int main(){}")));

            // The source is the thing worth most when a submission fails, because the
            // contestant cannot leave the window to go and find it again.
            verify(archive).markRejected(eq("arch-1"), anyString());
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
            attachAccount();
            when(cache.problems(CREDS, CID)).thenReturn(List.of(problem("p1", "A")));
            when(cache.judgementTypes(CREDS, CID)).thenReturn(Map.of(
                "AC", type("AC", true),
                "WA", type("WA", false)));
        }

        @Test
        @DisplayName("an accepted submission reads as OK, which is what marks a problem solved")
        void accepted() {
            when(cache.submissions(CREDS, CID)).thenReturn(List.of(submission("1", TEAM, "p1")));
            when(cache.judgementsBySubmission(CREDS, CID))
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
            when(cache.submissions(CREDS, CID)).thenReturn(List.of(submission("1", TEAM, "p1")));
            when(cache.judgementsBySubmission(CREDS, CID))
                .thenReturn(Map.of("1", judgement("1", "WA")));

            assertEquals("WRONG_ANSWER", provider.submissions(USER, CID).get(0).verdict());
        }

        @Test
        @DisplayName("no judgement yet is still judging, not a failure")
        void pending() {
            when(cache.submissions(CREDS, CID)).thenReturn(List.of(submission("1", TEAM, "p1")));
            when(cache.judgementsBySubmission(CREDS, CID)).thenReturn(Map.of());

            CompeteDto.ContestSubmission row = provider.submissions(USER, CID).get(0);
            assertEquals("TESTING", row.verdict());
            assertFalse(row.finished(), "a pending row must keep the console polling");
        }

        @Test
        @DisplayName("a judgement type this build does not know is shown as the judge named it")
        void unknownType() {
            when(cache.submissions(CREDS, CID)).thenReturn(List.of(submission("1", TEAM, "p1")));
            when(cache.judgementsBySubmission(CREDS, CID))
                .thenReturn(Map.of("1", judgement("1", "CUSTOM_REJECT")));

            assertEquals("CUSTOM_REJECT",
                provider.submissions(USER, CID).get(0).verdict(),
                "inventing a verdict for a custom type would misreport the judge");
        }

        @Test
        @DisplayName("only this team's submissions come back")
        void filtersToOwnTeam() {
            when(cache.submissions(CREDS, CID)).thenReturn(List.of(
                submission("1", TEAM, "p1"),
                submission("2", "someone-else", "p1")));
            when(cache.judgementsBySubmission(CREDS, CID)).thenReturn(Map.of());

            List<CompeteDto.ContestSubmission> rows = provider.submissions(USER, CID);

            assertEquals(1, rows.size());
            assertEquals("1", rows.get(0).id());
        }

        @Test
        @DisplayName("a contestant with no attached account sees an empty list, not an error")
        void noAccount() {
            when(credentials.find(USER)).thenReturn(null);

            assertTrue(provider.submissions(USER, CID).isEmpty());
        }
    }

    // ------------------------------------------------------------ leaderboard

    @Nested
    @DisplayName("the full board, as the judge ranks it")
    class Leaderboard {

        private DjModels.Row row(Integer rank, String teamId, int solved, Integer penalty,
                                 boolean solvedA, Integer minuteA) {
            DjModels.Problem problem = new DjModels.Problem();
            problem.setLabel("A");
            problem.setProblem_id("p1");
            problem.setSolved(solvedA);
            problem.setNum_judged(2);
            problem.setTime(minuteA);

            DjModels.Score score = new DjModels.Score();
            score.setNum_solved(solved);
            score.setTotal_time(penalty);

            DjModels.Row r = new DjModels.Row();
            r.setRank(rank);
            r.setTeam_id(teamId);
            r.setScore(score);
            r.setProblems(List.of(problem));
            return r;
        }

        private DjModels.Scoreboard board(DjModels.Row... rows) {
            DjModels.Scoreboard b = new DjModels.Scoreboard();
            b.setRows(List.of(rows));
            return b;
        }

        private DjModels.Team team(String id, String name) {
            DjModels.Team t = new DjModels.Team();
            t.setId(id);
            t.setDisplay_name(name);
            return t;
        }

        @BeforeEach
        void common() {
            attachAccount();
            when(cache.problems(CREDS, CID)).thenReturn(List.of(problem("p1", "A")));
            when(cache.state(CREDS, CID))
                .thenReturn(state("2026-09-03T09:00:00+00:00", null, null, null));
            when(cache.teams(CREDS, CID)).thenReturn(List.of(
                team(TEAM, "Team Alpha"), team("t8", "Team Beta")));
        }

        @Test
        @DisplayName("every team is returned, with the viewer's own row marked and repeated")
        void marksOwnTeam() {
            when(cache.scoreboard(CREDS, CID)).thenReturn(board(
                row(1, "t8", 2, 100, true, 40),
                row(2, TEAM, 1, 55, true, 55)));

            CompeteDto.Leaderboard board = provider.leaderboard(USER, CID);

            assertEquals(2, board.rows().size());
            assertEquals("Team Beta", board.rows().get(0).teamName());
            assertFalse(board.rows().get(0).mine());

            assertTrue(board.rows().get(1).mine());
            assertNotNull(board.myTeam(), "the own row is repeated so the page need not search");
            assertEquals(TEAM, board.myTeam().teamId());
            assertEquals(1, board.myTeam().solved());
            assertEquals(List.of("A"), board.problemIndexes());
        }

        @Test
        @DisplayName("an unsolved problem reports no minute, rather than minute zero")
        void unsolvedHasNoMinute() {
            // DOMjudge sends 0 for a problem nobody has solved. Passing that through would
            // render as "solved at minute 0", which is the most confident possible way to be
            // wrong on a scoreboard.
            when(cache.scoreboard(CREDS, CID)).thenReturn(board(
                row(1, TEAM, 0, 0, false, 0)));

            CompeteDto.LeaderboardCell cell = provider.leaderboard(USER, CID)
                .rows().get(0).problems().get(0);

            assertFalse(cell.solved());
            assertNull(cell.minute());
            assertEquals(2, cell.attempts(), "attempts still count when none of them worked");
        }

        @Test
        @DisplayName("a board read from the public view is reported as not live")
        void publicBoardIsNotLive() {
            when(cache.scoreboard(CREDS, CID)).thenReturn(board(row(1, TEAM, 1, 20, true, 20)));
            when(domjudge.hasServiceAccount()).thenReturn(false);
            when(domjudge.canReadJuryScoreboard(CREDS, CID)).thenReturn(false);

            // A frozen board that claims to be live looks exactly like a room where nobody is
            // solving anything, so the page has to be able to say which it is looking at.
            assertFalse(provider.leaderboard(USER, CID).live());
        }

        @Test
        @DisplayName("team names degrade to ids rather than failing the whole panel")
        void namesAreOptional() {
            when(cache.teams(CREDS, CID)).thenThrow(new RuntimeException("403"));
            when(cache.scoreboard(CREDS, CID)).thenReturn(board(row(1, TEAM, 1, 20, true, 20)));

            CompeteDto.Leaderboard board = provider.leaderboard(USER, CID);

            assertEquals(1, board.rows().size());
            assertEquals(TEAM, board.rows().get(0).teamName());
        }

        @Test
        @DisplayName("no board yet is an empty one, not an error")
        void emptyBoard() {
            when(cache.scoreboard(CREDS, CID)).thenReturn(null);

            CompeteDto.Leaderboard board = provider.leaderboard(USER, CID);

            assertTrue(board.rows().isEmpty());
            assertNull(board.myTeam());
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
