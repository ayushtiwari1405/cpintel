package com.cpintel.compete;

import com.cpintel.archive.SubmissionArchive;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.GroupMember;
import com.cpintel.exception.ApiException;
import com.cpintel.files.ContestFilePolicy;
import com.cpintel.integration.domjudge.DjModels;
import com.cpintel.integration.domjudge.DjTime;
import com.cpintel.integration.domjudge.DomjudgeClient;
import com.cpintel.integration.domjudge.DomjudgeSampleClient;
import com.cpintel.practice.PracticeDto;
import com.cpintel.repository.jpa.GroupContestRepository;
import com.cpintel.repository.jpa.GroupMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p><b>Identity.</b> CPIntel submits as an admin acting on a team's behalf, so no contestant
 * ever types a DOMjudge password and there is nothing per-user to set up on contest morning.
 * The mapping from a CPIntel account to a DOMjudge team is the group membership an admin
 * already fills in for the standings board — {@link GroupMember#getExternalHandle()}, the team
 * name — so the same field drives both, and a contestant who is not in a group laid over this
 * contest simply cannot submit. That is the correct answer rather than a limitation: a DOMjudge
 * round here is always somebody's round.
 *
 * <p><b>Fan-out.</b> Nothing in this class calls the judge per contestant. Every read goes
 * through {@link DomjudgeContestCache}, which holds one copy per contest. Two hundred people
 * polling for verdicts every five seconds cost one submissions fetch every few seconds between
 * them, and the judge spends its cycles compiling code rather than serving the same scoreboard
 * two hundred times.
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
    private final GroupContestRepository contestRepository;
    private final GroupMemberRepository memberRepository;
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

    // ------------------------------------------------------------ contest load

    @Override
    public CompeteDto.ContestInfo contestInfo(Long userId, String contestId) {
        DjModels.Contest meta = cache.contest(contestId);
        if (meta == null) {
            throw ApiException.notFound(
                "DOMjudge has no contest '" + contestId + "', or this account cannot see it.");
        }

        DjModels.State state = cache.state(contestId);

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
        for (DjModels.ContestProblem problem : cache.problems(contestId)) {
            problems.add(new CompeteDto.ContestProblem(
                problem.getLabel(), problem.getName(), null, null));
        }

        String teamId = teamIdFor(userId, contestId);
        boolean canSubmit = false;
        String closedReason = null;
        if (teamId == null) {
            closedReason = "You are not registered as a team on this contest. Ask the admin to "
                + "add your DOMjudge team name to the group running it.";
        } else if (!running) {
            closedReason = ended != null
                ? "This contest has finished."
                : "The contest has not started yet.";
        } else if (submittableLanguages(contestId).isEmpty()) {
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
        DjModels.ContestProblem problem = requireProblem(contestId, index);

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
            samples.samples(contestId, problem.getId()),
            domjudge.root() + "/team/problems/" + problem.getId(),
            true,
            "/api/v1/compete/DOMJUDGE/" + contestId + "/problems/" + problem.getLabel()
                + "/statement.pdf",
            null);
    }

    @Override
    public byte[] statementPdf(Long userId, String contestId, String index) {
        DjModels.ContestProblem problem = requireProblem(contestId, index);
        byte[] pdf = domjudge.getStatement(contestId, problem.getId());
        if (pdf == null || pdf.length == 0) {
            throw ApiException.notFound(
                "Problem " + problem.getLabel() + " has no statement attached in DOMjudge.");
        }
        return pdf;
    }

    @Override
    public List<PracticeDto.LanguageOption> languages(Long userId, String contestId) {
        List<PracticeDto.LanguageOption> out = new ArrayList<>();
        for (DjModels.Language language : submittableLanguages(contestId)) {
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
    private List<DjModels.Language> submittableLanguages(String contestId) {
        List<DjModels.Language> out = new ArrayList<>();
        for (DjModels.Language language : cache.languages(contestId)) {
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

        String teamId = teamIdFor(userId, contestId);
        if (teamId == null) {
            throw ApiException.forbidden(
                "You are not registered as a team on this contest, so there is nowhere to "
                    + "submit. Ask the admin to add your DOMjudge team name to the group.");
        }

        DjModels.ContestProblem problem = requireProblem(contestId, req.index());
        DjModels.Language language = submittableLanguages(contestId).stream()
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
            submissionId = domjudge.submit(contestId, teamId, problem.getId(),
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
     * and come back to.
     */
    @Override
    public List<CompeteDto.ContestSubmission> submissions(Long userId, String contestId) {
        String teamId = teamIdFor(userId, contestId);
        if (teamId == null) return List.of();

        Map<String, DjModels.Judgement> judgements = cache.judgementsBySubmission(contestId);
        Map<String, DjModels.JudgementType> types = cache.judgementTypes(contestId);

        Map<String, DjModels.ContestProblem> problemsById = new java.util.HashMap<>();
        for (DjModels.ContestProblem problem : cache.problems(contestId)) {
            if (problem.getId() != null) problemsById.put(problem.getId(), problem);
        }

        List<CompeteDto.ContestSubmission> out = new ArrayList<>();
        for (DjModels.Submission submission : cache.submissions(contestId)) {
            if (!teamId.equals(submission.getTeam_id())) continue;

            DjModels.Judgement judgement = judgements.get(submission.getId());
            String verdict = verdictOf(judgement, types);
            DjModels.ContestProblem problem = problemsById.get(submission.getProblem_id());

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

    private Integer runtimeMillis(DjModels.Judgement judgement) {
        if (judgement == null || judgement.getMax_run_time() == null) return null;
        return (int) Math.round(judgement.getMax_run_time() * 1000);
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
        String teamId = teamIdFor(userId, contestId);
        DjModels.State state = cache.state(contestId);
        boolean frozen = state != null && state.getFrozen() != null && state.getThawed() == null;

        if (teamId == null) {
            return new CompeteDto.RankInfo(null, null, null, 0, frozen, false, Instant.now());
        }

        DjModels.Scoreboard board = cache.scoreboard(contestId);
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

    /**
     * The DOMjudge team this CPIntel account competes as, or null.
     *
     * Resolved through the group laid over this contest, because that is where an admin
     * already records the team name for the standings board. Null is a normal answer — someone
     * opening a contest they are not enrolled in — and every caller treats it as "cannot
     * submit" rather than as an error.
     */
    private String teamIdFor(Long userId, String contestId) {
        String teamName = teamNameFor(userId, contestId);
        if (teamName == null) return null;

        String teamId = cache.teamIdByName(contestId, teamName);
        if (teamId == null) {
            log.debug("No DOMjudge team named '{}' in contest {} for user {}",
                teamName, contestId, userId);
        }
        return teamId;
    }

    private String teamNameFor(Long userId, String contestId) {
        List<GroupContest> candidates = contestRepository.findForParticipant(
            userId, GroupContest.Platform.DOMJUDGE.name(), contestId);

        for (GroupContest contest : candidates) {
            Optional<GroupMember> member = memberRepository.findByGroupGroupIdAndUserUserId(
                contest.getGroup().getGroupId(), userId);
            if (member.isPresent() && StringUtils.hasText(member.get().getExternalHandle())) {
                return member.get().getExternalHandle();
            }
        }
        return null;
    }

    private DjModels.ContestProblem requireProblem(String contestId, String index) {
        String wanted = index == null ? "" : index.trim();
        return cache.problems(contestId).stream()
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
