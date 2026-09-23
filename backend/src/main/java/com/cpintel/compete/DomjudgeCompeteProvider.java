package com.cpintel.compete;

import com.cpintel.archive.SubmissionArchive;
import com.cpintel.exception.ApiException;
import com.cpintel.files.ContestFilePolicy;
import com.cpintel.integration.domjudge.DjModels;
import com.cpintel.integration.domjudge.DjTime;
import com.cpintel.integration.domjudge.DomjudgeClient;
import com.cpintel.integration.domjudge.DomjudgeCredentialStore;
import com.cpintel.integration.domjudge.DomjudgeSampleClient;
import com.cpintel.practice.PracticeDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a DOMjudge contest from the same page the Codeforces arena uses.
 *
 * <p>Where the Codeforces provider is an exercise in reading a service that does not want to be
 * read — scraping pages, working around a locked-down standings endpoint, inferring a virtual
 * window from a countdown — this one talks to a documented API on a machine the operator owns.
 * Almost everything hard about the Codeforces path simply is not here. What replaces it are two
 * decisions worth stating.
 *
 * <p><b>Identity.</b> CPIntel competes as the contestant's own DOMjudge account, which an admin
 * attaches once through {@link com.cpintel.integration.domjudge.DomjudgeAccountService}. The
 * contestant never types a DOMjudge password; the admin provisions it before the round and the
 * arena replays it. Two consequences follow, and both are improvements on the admin-on-behalf
 * arrangement this replaced. The judge attributes each submission to the team behind the
 * account, so no mapping inside CPIntel can drift out of step with the judge's own roster. And
 * the contest list is whatever DOMjudge is willing to show that account, so a contestant sees
 * exactly the rounds they are registered for without anybody maintaining a second roster here.
 *
 * <p><b>Fan-out.</b> Nothing in this class calls the judge per contestant <em>when it does not
 * have to</em>. Every read goes through {@link DomjudgeContestCache}. Where the deployment has
 * a service account, one fetch serves the whole room. Where it does not, reads are made as each
 * contestant and cached per account — because DOMjudge filters a team account's view of the
 * submissions list to its own team, so sharing those entries between contestants would show
 * people each other's verdicts. {@link #readAs} is the single place that decides which, and the
 * trade is explicit rather than silent: no service account means no fan-out.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DomjudgeCompeteProvider implements CompeteProvider {

    /**
     * DOMjudge verdict codes, mapped onto the names the arena already renders.
     *
     * Deliberately reusing the Codeforces vocabulary rather than inventing a second one: the
     * submissions list, the problem navigator's solved marker and the console all key off these
     * strings, and a parallel set would mean every one of them growing a branch per judge.
     */
    private static final Map<String, String> VERDICTS = Map.of(
        "AC",  "OK",
        "WA",  "WRONG_ANSWER",
        "TLE", "TIME_LIMIT_EXCEEDED",
        "RTE", "RUNTIME_ERROR",
        "CE",  "COMPILATION_ERROR",
        "MLE", "MEMORY_LIMIT_EXCEEDED",
        "OLE", "OUTPUT_LIMIT_EXCEEDED",
        "NO",  "REJECTED");

    /** A contest id out of a DOMjudge URL, or the bare id on its own. */
    private static final Pattern CONTEST_ID = Pattern.compile(
        "contests?/([A-Za-z0-9_.-]+)|^\\s*([A-Za-z0-9_.-]{1,64})\\s*$");

    private final DomjudgeClient domjudge;
    private final DomjudgeContestCache cache;
    private final DomjudgeSampleClient samples;
    private final DomjudgeCredentialStore credentials;
    private final SubmissionArchive archive;
    private final ContestFilePolicy filePolicy;

    @Override
    public String platform() {
        return CompeteDto.Platform.DOMJUDGE.name();
    }

    @Override
    public String parseContestId(String raw) {
        if (!domjudge.isConfigured()) {
            throw ApiException.badRequest(
                "No DOMjudge instance is configured on this deployment.");
        }
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("Paste a DOMjudge contest link, or its contest id.");
        }
        Matcher m = CONTEST_ID.matcher(raw.trim());
        if (!m.find()) {
            throw ApiException.badRequest(
                "That does not look like a DOMjudge contest. Expected an id such as "
                    + "'nwerc18', or a link like https://judge.example.edu/contests/3");
        }
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    // ------------------------------------------------------------ identity

    /**
     * Which credentials a contest-wide read should be made with.
     *
     * Null selects the deployment's service account, whose answers every contestant shares.
     * Returning the contestant's own credentials instead costs the fan-out but is the only
     * correct answer when there is no service account — see the class comment.
     */
    private DomjudgeCredentialStore.Stored readAs(DomjudgeCredentialStore.Stored own) {
        return domjudge.hasServiceAccount() ? null : own;
    }

    // ------------------------------------------------------------ contest load

    @Override
    public CompeteDto.ContestInfo contestInfo(Long userId, String contestId) {
        DomjudgeCredentialStore.Stored own = credentials.find(userId);
        DomjudgeCredentialStore.Stored as = readAs(own);

        // Without an attached account there is nothing to read the contest as, and on a
        // deployment with no service account there is literally no way to answer. Saying so
        // here beats a 403 from the judge that reads as though the contest does not exist.
        if (own == null && !domjudge.hasServiceAccount()) {
            throw ApiException.forbidden(
                "No DOMjudge account is attached to your CPIntel account, so this contest "
                    + "cannot be opened. Ask the admin running it to attach one.");
        }

        DjModels.Contest meta = cache.contest(as, contestId);
        if (meta == null) {
            throw ApiException.notFound(
                "DOMjudge has no contest '" + contestId + "', or this account cannot see it.");
        }

        DjModels.State state = cache.state(as, contestId);

        // Timestamps, not booleans — a contest is live when it has started and not ended.
        Instant started = state == null ? null : DjTime.instant(state.getStarted());
        Instant ended = state == null ? null : DjTime.instant(state.getEnded());
        boolean frozen = state != null && state.getFrozen() != null && state.getThawed() == null;

        boolean running = started != null && ended == null;
        String phase = ended != null ? "FINISHED" : (running ? "CODING" : "BEFORE");

        Instant startsAt = DjTime.instant(meta.getStart_time());
        long duration = DjTime.seconds(meta.getDuration(), 0);

        long now = Instant.now().getEpochSecond();
        long untilStart = startsAt == null ? 0 : startsAt.getEpochSecond() - now;
        long remaining = 0;
        if (running) {
            Instant endsAt = DjTime.instant(meta.getEnd_time());
            if (endsAt == null && startsAt != null && duration > 0) {
                endsAt = startsAt.plusSeconds(duration);
            }
            remaining = endsAt == null ? 0 : Math.max(0, endsAt.getEpochSecond() - now);
        }

        List<CompeteDto.ContestProblem> problems = new ArrayList<>();
        for (DjModels.ContestProblem problem : cache.problems(as, contestId)) {
            problems.add(new CompeteDto.ContestProblem(
                problem.getLabel(), problem.getName(), null, null));
        }

        boolean canSubmit = false;
        String closedReason = null;
        if (own == null) {
            closedReason = "No DOMjudge account is attached to your CPIntel account, so there "
                + "is nothing to submit as. Ask the admin running this contest to attach one.";
        } else if (!running) {
            closedReason = ended != null
                ? "This contest has finished."
                : "The contest has not started yet.";
        } else if (submittableLanguages(as, contestId).isEmpty()) {
            closedReason = "This contest is not accepting submissions in any language.";
        } else {
            canSubmit = true;
        }

        String name = StringUtils.hasText(meta.getFormal_name())
            ? meta.getFormal_name()
            : (StringUtils.hasText(meta.getName()) ? meta.getName() : "Contest " + contestId);

        return new CompeteDto.ContestInfo(
            contestId, name, CompeteDto.Platform.DOMJUDGE.name(),
            phase, running, frozen,
            startsAt, duration, untilStart, remaining,
            canSubmit, closedReason,
            filePolicy.enabledFor(CompeteDto.Platform.DOMJUDGE.name(), contestId),
            CompeteDto.StatementFormat.PDF,
            problems,
            domjudge.root() + "/team");
    }

    // -------------------------------------------------------------- statements

    /**
     * Problem metadata plus a pointer at the statement PDF.
     *
     * DOMjudge ships statements as the PDF from the problem package rather than as a web page,
     * so there is no legend to parse and nothing to restyle — the arena embeds the document as
     * the judge built it. The samples beside it come from the package's own sample data, which
     * is what makes the testcase console usable.
     */
    @Override
    public PracticeDto.ProblemDetail statement(Long userId, String contestId, String index) {
        DomjudgeCredentialStore.Stored as = readAs(credentials.find(userId));
        DjModels.ContestProblem problem = requireProblem(as, contestId, index);

        String timeLimit = problem.getTime_limit() == null
            ? null : trimNumber(problem.getTime_limit()) + " seconds";

        return new PracticeDto.ProblemDetail(
            contestId,
            problem.getLabel(),
            StringUtils.hasText(problem.getName()) ? problem.getName() : problem.getLabel(),
            null,
            List.of(),
            timeLimit,
            // DOMjudge enforces a memory limit per judgehost rather than per problem, and does
            // not publish it here. Stating nothing beats stating a number this cannot know.
            null,
            null, null,
            null, null, null, null,
            samples.samples(as, contestId, problem.getId()),
            domjudge.root() + "/team/problems/" + problem.getId(),
            true,
            "/api/v1/compete/DOMJUDGE/" + contestId + "/problems/" + problem.getLabel()
                + "/statement.pdf",
            null);
    }

    @Override
    public CompeteDto.StatementDocument statementDocument(Long userId, String contestId,
                                                          String index) {
        DomjudgeCredentialStore.Stored own = credentials.find(userId);
        DomjudgeCredentialStore.Stored as = readAs(own);
        DjModels.ContestProblem problem = requireProblem(as, contestId, index);

        // Fetched as the contestant, not the service account. On DOMjudge 8.0 the only route
        // that serves a running contest's statement is the team page, which needs a signed-in
        // team session — and the service account has no team to sign in as.
        DomjudgeClient.Statement statement =
            domjudge.getStatement(own != null ? own : as, contestId, problem.getId());
        if (statement == null || statement.isEmpty()) {
            // Deliberately not "this problem has no statement", which is what this said while
            // the real cause was CPIntel calling a route this DOMjudge does not have. That
            // sentence sent people to look at the problem package, which was fine.
            throw ApiException.notFound(
                "Could not read the statement for problem " + problem.getLabel()
                + " from DOMjudge. Either the problem has no statement attached, or this "
                + "instance does not publish statements on any route CPIntel knows — run "
                + "scripts/domjudge-probe.sh to tell the two apart.");
        }
        return new CompeteDto.StatementDocument(statement.bytes(), statement.contentType());
    }

    @Override
    public List<PracticeDto.LanguageOption> languages(Long userId, String contestId) {
        DomjudgeCredentialStore.Stored as = readAs(credentials.find(userId));

        List<PracticeDto.LanguageOption> out = new ArrayList<>();
        for (DjModels.Language language : submittableLanguages(as, contestId)) {
            out.add(new PracticeDto.LanguageOption(
                language.getId(),
                StringUtils.hasText(language.getName()) ? language.getName() : language.getId()));
        }
        return out;
    }

    /**
     * The languages this contest will actually take.
     *
     * {@code allow_submit} is absent on older DOMjudge builds, and a null there is treated as
     * permitted: offering a language the judge then refuses produces one clear error, whereas
     * hiding every language would leave a contestant with an empty picker and no explanation.
     */
    private List<DjModels.Language> submittableLanguages(DomjudgeCredentialStore.Stored as,
                                                         String contestId) {
        List<DjModels.Language> out = new ArrayList<>();
        for (DjModels.Language language : cache.languages(as, contestId)) {
            if (language.getId() == null) continue;
            if (Boolean.FALSE.equals(language.getAllow_submit())) continue;
            out.add(language);
        }
        return out;
    }

    // ------------------------------------------------------------- submitting

    @Override
    public CompeteDto.ContestSubmission submit(Long userId, String contestId,
                                               CompeteDto.ContestSubmitRequest req) {
        if (req.source().isBlank()) {
            throw ApiException.badRequest("There is no code to submit.");
        }

        // The contestant's own credentials, not the read scope: a submission is the one
        // operation that must never be made as the service account, because the judge would
        // then attribute it to the service account's team rather than to theirs.
        DomjudgeCredentialStore.Stored own = credentials.require(userId);
        DomjudgeCredentialStore.Stored as = readAs(own);

        DjModels.ContestProblem problem = requireProblem(as, contestId, req.index());
        DjModels.Language language = submittableLanguages(as, contestId).stream()
            .filter(l -> l.getId().equals(req.languageId()))
            .findFirst()
            .orElseThrow(() -> ApiException.badRequest(
                "This contest does not accept " + req.languageId() + "."));

        // Archived before it leaves the building, exactly as on the Codeforces path: the one
        // moment the source is worth the most is when the submission failed and the contestant
        // cannot leave the window to go and find it again.
        String archiveId = archive.recordAttempt(userId, SubmissionArchive.DOMJUDGE,
            contestId, problem.getLabel(), problem.getName(),
            language.getId(), language.getName(), req.source());

        String submissionId;
        try {
            submissionId = domjudge.submitAs(own, contestId, problem.getId(),
                language.getId(), fileNameFor(problem, language), req.source());
        } catch (ApiException e) {
            archive.markRejected(archiveId, e.getMessage());
            throw e;
        }
        archive.attachExternalId(archiveId, submissionId);

        // The judge has the submission but almost certainly has not judged it yet. Rather than
        // sleeping and hoping, the new row is returned as pending and the console's own polling
        // picks up the verdict — which it is already doing for every other submission on screen.
        cache.evict(contestId);

        return new CompeteDto.ContestSubmission(
            submissionId, problem.getLabel(), problem.getName(), language.getName(),
            "TESTING", null, null, null, Instant.now(), false,
            domjudge.root() + "/team/submissions/" + submissionId);
    }

    /**
     * A plausible file name for the submitted source.
     *
     * DOMjudge records the name and shows it to the jury, and some configurations lean on the
     * extension when a language maps to several. Naming it after the problem label means a
     * jury member looking at a submission sees which problem it was for.
     */
    private String fileNameFor(DjModels.ContestProblem problem, DjModels.Language language) {
        String extension = language.getExtensions() == null || language.getExtensions().isEmpty()
            ? "txt" : language.getExtensions().get(0);
        String base = problem.getLabel() == null ? "solution" : problem.getLabel();
        return base.toLowerCase(java.util.Locale.ROOT) + "." + extension;
    }

    /**
     * This contestant's submissions, filtered out of the contest-wide list.
     *
     * The filter is deliberately on the team the judge recorded, not on anything CPIntel
     * remembers about who pressed submit: a submission made directly in DOMjudge's own UI shows
     * up here too, which is what a contestant expects and what makes the arena safe to leave
     * and come back to. The team comes from the attached account, so it is the judge's own
     * answer rather than a mapping that could be stale.
     */
    /**
     * The judge's id for this contestant's team, resolved however it can be.
     *
     * <h2>Why this is not simply {@code own.teamId()}</h2>
     *
     * <p>It often is, and on DOMjudge 8.2 and later it always is. On 8.0 {@code /user} reports
     * only the team's <em>name</em>, so attaching an account there stores a name and a null id
     * — which {@link com.cpintel.integration.domjudge.DomjudgeAccountService} accepts
     * deliberately, on the stated grounds that the name alone still identifies the team.
     *
     * <p>Three readers here then disagreed with that and treated a null id as "this person has
     * no team": their submissions list came back empty, their row was never pinned on the
     * leaderboard, and their rank was reported as nothing. From the contestant's side that
     * looks exactly like the judge losing their work — the submission is accepted, the judge
     * shows a verdict on its own site, and CPIntel shows an empty list forever.
     *
     * <p>So the name is turned into an id against this contest's team list, which is the same
     * lookup the standings provider does and is already cached. A contestant whose team is not
     * registered for the contest still resolves to null, which is the one case where "no rows"
     * is the truth.
     *
     * <p>Deliberately the judge's team rather than {@code effectiveTeamId()}: an admin's
     * assignment governs how CPIntel groups somebody for standings, and cannot govern which
     * submissions are theirs — the judge files those against the login.
     */
    private String judgeTeamId(DomjudgeCredentialStore.Stored own,
                               DomjudgeCredentialStore.Stored as, String contestId) {
        if (own == null) return null;
        if (StringUtils.hasText(own.teamId())) return own.teamId();
        return cache.teamIdByName(as, contestId, own.teamName());
    }

    @Override
    public List<CompeteDto.ContestSubmission> submissions(Long userId, String contestId) {
        DomjudgeCredentialStore.Stored own = credentials.find(userId);
        if (own == null) return List.of();
        DomjudgeCredentialStore.Stored as = readAs(own);

        String myTeamId = judgeTeamId(own, as, contestId);

        /*
         * Whether the rows still have to be filtered by team here.
         *
         * DOMjudge already scopes /submissions to the caller's own team when the caller is a
         * team account — which is every read on a deployment with no service account, since
         * readAs then hands back the contestant's own credentials. A service account, by
         * contrast, sees the whole contest and its answers are shared between contestants, so
         * there the filter is the only thing keeping one person's submissions out of another's
         * list. It is not optional there and it is redundant here.
         *
         * That distinction is what makes an unresolvable team id survivable. This method used
         * to return an empty list the moment the id was null, which on an 8.0 instance — where
         * /user reports a team name and no id — meant every contestant saw an empty submission
         * list and no verdicts, for ever, while the judge showed their work perfectly well on
         * its own site. Trusting the judge's own scoping in exactly the case where it applies
         * turns that from a dead feature into a working one.
         */
        boolean judgeScopesToUs = own == as;
        if (myTeamId == null && !judgeScopesToUs) {
            // Read through a service account with no way to tell whose rows are whose. Empty
            // is the only safe answer, and it is worth saying loudly rather than looking like
            // somebody who has not submitted.
            log.warn("Cannot tell which submissions belong to user {} in contest {}: the "
                + "attached DOMjudge account resolves to no team id, and reads go through the "
                + "service account. Re-attach the account, or register team '{}' for the "
                + "contest.", userId, contestId, own.teamName());
            return List.of();
        }

        /*
         * Verdicts, if the judge will give them.
         *
         * Degraded rather than fatal, which it was not before. /contests/{cid}/judgements is an
         * authenticated route and not every account is allowed it on every installation; a cold
         * refusal propagates out of the cache, and that used to take the whole submissions list
         * with it. The contestant then saw nothing at all — not their own submissions, which
         * came from a different call that had worked fine.
         *
         * Without verdicts each row reads as TESTING, which is what verdictOf answers for a
         * missing judgement. That is the honest rendering of "sent, not yet known here", and it
         * leaves the contestant looking at their submissions with a link to the judge rather
         * than at an error.
         */
        Map<String, DjModels.Judgement> judgements;
        try {
            judgements = cache.judgementsBySubmission(as, contestId);
        } catch (Exception e) {
            log.warn("Could not read judgements for contest {} as user {} ({}); submissions "
                + "will show without verdicts", contestId, userId, e.getMessage());
            judgements = Map.of();
        }

        Map<String, DjModels.JudgementType> types;
        try {
            types = cache.judgementTypes(as, contestId);
        } catch (Exception e) {
            // Only affects how a verdict code is labelled, never whether one is shown.
            log.debug("Could not read judgement types for contest {}: {}",
                contestId, e.getMessage());
            types = Map.of();
        }

        Map<String, DjModels.ContestProblem> problemsById = new java.util.HashMap<>();
        for (DjModels.ContestProblem problem : cache.problems(as, contestId)) {
            if (problem.getId() != null) problemsById.put(problem.getId(), problem);
        }

        List<CompeteDto.ContestSubmission> out = new ArrayList<>();
        for (DjModels.Submission submission : cache.submissions(as, contestId)) {
            if (myTeamId != null && !myTeamId.equals(submission.getTeam_id())) continue;

            DjModels.Judgement judgement = judgements.get(submission.getId());
            String verdict = verdictOf(judgement, types);
            DjModels.ContestProblem problem = problemsById.get(submission.getProblem_id());

            // The archive files an attempt as TESTING when it is sent and nothing else moves it
            // on, so "Your previous code" showed every DOMjudge attempt as judging for ever.
            // This poll is where the final verdict first becomes known; a row already carrying
            // it is skipped inside updateVerdict, so the steady state costs no writes.
            if (!"TESTING".equals(verdict)) {
                archiveVerdict(userId, submission.getId(), verdict, runtimeMillis(judgement));
            }

            out.add(new CompeteDto.ContestSubmission(
                submission.getId(),
                problem == null ? null : problem.getLabel(),
                problem == null ? null : problem.getName(),
                submission.getLanguage_id(),
                verdict,
                // DOMjudge reports a verdict, not a count of tests passed; inventing one would
                // put a number on screen that nothing on the judge agrees with.
                null,
                runtimeMillis(judgement),
                null,
                DjTime.instant(submission.getTime()),
                !"TESTING".equals(verdict),
                domjudge.root() + "/team/submissions/" + submission.getId()));
        }

        out.sort(Comparator.comparing(
            CompeteDto.ContestSubmission::createdAt,
            Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    /**
     * A judgement's verdict, in the arena's vocabulary.
     *
     * A submission with no judgement yet, or one whose judgement has no type, is still being
     * judged — which is the common case for the few seconds after a submit and is what the
     * console renders as "Judging".
     */
    private String verdictOf(DjModels.Judgement judgement,
                             Map<String, DjModels.JudgementType> types) {
        if (judgement == null || judgement.getJudgement_type_id() == null) return "TESTING";

        String code = judgement.getJudgement_type_id();
        DjModels.JudgementType type = types.get(code);
        if (type != null && Boolean.TRUE.equals(type.getSolved())) return "OK";

        // An unmapped code is shown as the judge's own label rather than guessed at. A custom
        // judgement type is a thing DOMjudge installations really do add.
        return VERDICTS.getOrDefault(code, code);
    }

    /** DOMjudge ids are numeric strings; the archive keys attempts by the number. */
    private void archiveVerdict(Long userId, String submissionId, String verdict, Integer timeMs) {
        if (submissionId == null) return;
        try {
            archive.updateVerdict(userId, SubmissionArchive.DOMJUDGE,
                Long.parseLong(submissionId.trim()), verdict, null, timeMs, null);
        } catch (NumberFormatException e) {
            // attachExternalId left such a row unlinked too, so there is nothing to update.
        }
    }

    private Integer runtimeMillis(DjModels.Judgement judgement) {
        if (judgement == null || judgement.getMax_run_time() == null) return null;
        return (int) Math.round(judgement.getMax_run_time() * 1000);
    }

    // ------------------------------------------------------------ leaderboard

    /**
     * The contest's whole board, as the judge ranks it.
     *
     * Read rather than recomputed. Every number here — rank, solved count, penalty, the minute
     * a problem went green — is DOMjudge's own, so the board CPIntel shows and the board on the
     * judge's wall projector agree. Recomputing from the submissions list would have produced a
     * second opinion that diverges the first time a rejudge lands.
     *
     * <p>Team names come from the teams endpoint rather than the scoreboard, which carries only
     * ids. Where that read is refused the row still renders, under the team id — a board with
     * one unnamed row beats no board at all, and the viewer's own row is identified by id
     * anyway.
     */
    @Override
    public CompeteDto.Leaderboard leaderboard(Long userId, String contestId) {
        DomjudgeCredentialStore.Stored own = credentials.find(userId);
        DomjudgeCredentialStore.Stored as = readAs(own);

        DjModels.State state = cache.state(as, contestId);
        boolean frozen = state != null && state.getFrozen() != null && state.getThawed() == null;

        DjModels.Scoreboard board = cache.scoreboard(as, contestId);
        String myTeamId = judgeTeamId(own, as, contestId);

        if (board == null || board.getRows() == null) {
            return new CompeteDto.Leaderboard(List.of(), myTeamId,
                own == null ? null : own.teamName(), null, List.of(),
                frozen, true, Instant.now());
        }

        Map<String, String> teamNames = new java.util.HashMap<>();
        try {
            for (DjModels.Team team : cache.teams(as, contestId)) {
                if (team.getId() == null) continue;
                teamNames.put(team.getId(), StringUtils.hasText(team.getDisplay_name())
                    ? team.getDisplay_name() : team.getName());
            }
        } catch (Exception e) {
            // Refused to a team account on some installations. The board is still worth
            // showing, so this degrades to ids rather than failing the whole panel.
            log.debug("Could not read team names for contest {}: {}", contestId, e.getMessage());
        }

        // Column headers in the judge's own problem order, so the board reads left to right
        // the same way the problem navigator does.
        List<String> indexes = new ArrayList<>();
        for (DjModels.ContestProblem problem : cache.problems(as, contestId)) {
            if (problem.getLabel() != null) indexes.add(problem.getLabel());
        }

        List<CompeteDto.LeaderboardRow> rows = new ArrayList<>();
        CompeteDto.LeaderboardRow mine = null;

        for (DjModels.Row row : board.getRows()) {
            boolean isMine = myTeamId != null && myTeamId.equals(row.getTeam_id());

            int solved = row.getScore() == null || row.getScore().getNum_solved() == null
                ? 0 : row.getScore().getNum_solved();
            Integer penalty = row.getScore() == null ? null : row.getScore().getTotal_time();

            List<CompeteDto.LeaderboardCell> cells = new ArrayList<>();
            if (row.getProblems() != null) {
                for (DjModels.Problem problem : row.getProblems()) {
                    String label = StringUtils.hasText(problem.getLabel())
                        ? problem.getLabel() : problem.getProblem_id();
                    cells.add(new CompeteDto.LeaderboardCell(
                        label,
                        Boolean.TRUE.equals(problem.getSolved()),
                        problem.getNum_judged() == null ? 0 : problem.getNum_judged(),
                        // A minute is only meaningful once the problem is green; DOMjudge
                        // reports 0 for unsolved ones, which would render as "solved at 0".
                        Boolean.TRUE.equals(problem.getSolved()) ? problem.getTime() : null));
                }
            }

            CompeteDto.LeaderboardRow built = new CompeteDto.LeaderboardRow(
                row.getRank(), row.getTeam_id(),
                teamNames.getOrDefault(row.getTeam_id(), row.getTeam_id()),
                solved, penalty, isMine, cells);

            rows.add(built);
            if (isMine) mine = built;
        }

        // `live` reports whether the judge's own view was readable. A team account is usually
        // refused it and silently served the public board, which stops moving at the freeze —
        // the page says which of the two it is looking at rather than leaving a contestant to
        // conclude the room has gone quiet.
        boolean live = domjudge.hasServiceAccount()
            || domjudge.canReadJuryScoreboard(own, contestId);

        return new CompeteDto.Leaderboard(rows, myTeamId,
            own == null ? null : own.teamName(), mine, indexes,
            frozen, live, Instant.now());
    }

    // ------------------------------------------------------------------- rank

    /**
     * This contestant's row on the judge's own scoreboard.
     *
     * Read rather than recomputed, so it agrees with what DOMjudge shows — the Codeforces path
     * has to derive a position from submissions and carries a caveat saying so. During the
     * freeze the row is the judge's frozen view, and the page says so.
     */
    @Override
    public CompeteDto.RankInfo rank(Long userId, String contestId) {
        DomjudgeCredentialStore.Stored own = credentials.find(userId);
        DomjudgeCredentialStore.Stored as = readAs(own);

        DjModels.State state = cache.state(as, contestId);
        boolean frozen = state != null && state.getFrozen() != null && state.getThawed() == null;

        String teamId = judgeTeamId(own, as, contestId);
        if (teamId == null) {
            return new CompeteDto.RankInfo(null, null, null, 0, frozen, false, Instant.now());
        }

        DjModels.Scoreboard board = cache.scoreboard(as, contestId);
        if (board == null || board.getRows() == null) {
            return new CompeteDto.RankInfo(null, null, null, 0, frozen, true, Instant.now());
        }

        for (DjModels.Row row : board.getRows()) {
            if (!teamId.equals(row.getTeam_id())) continue;

            int solved = row.getScore() == null || row.getScore().getNum_solved() == null
                ? 0 : row.getScore().getNum_solved();
            Integer penalty = row.getScore() == null ? null : row.getScore().getTotal_time();

            return new CompeteDto.RankInfo(
                row.getRank(), (double) solved, penalty, solved, frozen, true, Instant.now());
        }

        // On the board's roster but with no row yet — normal before the first submission.
        return new CompeteDto.RankInfo(null, null, null, 0, frozen, true, Instant.now());
    }

    // ---------------------------------------------------------------- helpers

    private DjModels.ContestProblem requireProblem(DomjudgeCredentialStore.Stored as,
                                                   String contestId, String index) {
        String wanted = index == null ? "" : index.trim();
        return cache.problems(as, contestId).stream()
            .filter(p -> wanted.equalsIgnoreCase(p.getLabel()))
            .findFirst()
            .orElseThrow(() -> ApiException.notFound(
                "Contest " + contestId + " has no problem " + index + "."));
    }

    /** 2.0 reads better as "2"; 1.5 has to stay 1.5. */
    private String trimNumber(double value) {
        return value == Math.floor(value)
            ? String.valueOf((long) value)
            : String.valueOf(value);
    }
}
