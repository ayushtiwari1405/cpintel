package com.cpintel.archive;

import com.cpintel.entity.mongo.CodeSubmission;
import com.cpintel.exception.ApiException;
import com.cpintel.integration.codeforces.CfModels;
import com.cpintel.integration.codeforces.CfSubmissionsResponse;
import com.cpintel.integration.codeforces.CodeforcesClient;
import com.cpintel.practice.CfSessionStore;
import com.cpintel.practice.CfWebSubmitClient;
import com.cpintel.repository.mongo.CodeSubmissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Your own previous code, without opening Codeforces.
 *
 * The problem this solves is specific: reading back a solution you already submitted means
 * visiting the submission page, and a contest that locks the app down cannot also ask you to
 * go and browse another site. So the code has to be here.
 *
 * Two sources, merged into one list per problem:
 *
 *  - The archive. Every submission CPIntel sends is written here first, before it goes
 *    anywhere. Reading it back is a local database hit: instant, and it works with the
 *    network unplugged.
 *  - Codeforces. Everything submitted before this feature existed, or from another machine,
 *    or from the website directly. CPIntel knows these exist from the public API, and fetches
 *    the source on demand — one page load, only when the user actually opens one.
 *
 * The write-then-submit ordering is deliberate and is the whole reliability argument. If the
 * archive were written after a successful submit, then every failure mode that loses the
 * submission — dead session, refused code, Codeforces unreachable — would also lose the code,
 * in a UI where the user cannot alt-tab to recover it. Writing first means the worst case is
 * an archived attempt with no submission id next to it, which is exactly the case where
 * having the source back is worth the most.
 *
 * For a contest CPIntel runs itself (DOMjudge), only the first source exists: there is no
 * upstream to re-read from. That is why this is keyed by platform rather than assuming
 * Codeforces — the write path is already the one a self-hosted judge needs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubmissionArchive {

    public static final String CODEFORCES = "CODEFORCES";

    /**
     * The self-hosted judge.
     *
     * For a DOMjudge contest the archive is the <em>only</em> record of what was submitted that
     * CPIntel can read back — there is no public submission page to re-fetch source from the
     * way there is on Codeforces. That makes the write-then-submit ordering below matter more
     * here, not less.
     */
    public static final String DOMJUDGE = "DOMJUDGE";

    /** Cap on the "everything I have written" view, which is a browse rather than a search. */
    private static final int RECENT_LIMIT = 200;

    private final CodeSubmissionRepository repo;
    private final CfSessionStore sessionStore;
    private final CfWebSubmitClient submitClient;
    private final CodeforcesClient codeforcesClient;

    // ------------------------------------------------------------- write path

    /**
     * Archives source at the moment it is sent, before the platform has seen it.
     *
     * Never throws. A submission must not fail because the archive is unavailable — the user
     * asked to submit, not to file a copy — so a broken Mongo degrades this feature and
     * nothing else. Returns null when the write did not happen.
     */
    public String recordAttempt(Long userId, String platform, String contestId, String index,
                                String problemName, String languageId, String languageLabel,
                                String source) {
        try {
            Instant now = Instant.now();
            CodeSubmission row = CodeSubmission.builder()
                .userId(userId)
                .platform(platform)
                .contestId(String.valueOf(contestId))
                .problemIndex(index == null ? null : index.toUpperCase(Locale.ROOT))
                .problemName(problemName)
                .languageId(languageId)
                .languageLabel(languageLabel)
                .source(source)
                .sourceBytes(source.getBytes(StandardCharsets.UTF_8).length)
                .sourceHash(sha256(source))
                .verdict("SUBMITTING")
                .origin(CodeSubmission.Origin.SUBMITTED.name())
                .submittedAt(now)
                .updatedAt(now)
                .build();
            return repo.save(row).getId();
        } catch (Exception e) {
            log.warn("Could not archive source for user {} ({}{}): {}",
                userId, contestId, index, e.getMessage());
            return null;
        }
    }

    /**
     * Links an archived attempt to the submission id the platform gave it.
     *
     * DOMjudge hands its ids out as strings even though they are integers underneath, so this
     * overload takes the text and parses it. An id that is not a number leaves the row
     * unlinked rather than failing the submission — the source is already safely stored, which
     * is the part that cannot be recovered by other means.
     */
    public void attachExternalId(String archiveId, String externalId) {
        if (archiveId == null || externalId == null) return;
        try {
            attachExternalId(archiveId, Long.parseLong(externalId.trim()));
        } catch (NumberFormatException e) {
            log.debug("Submission id '{}' is not numeric; archive row {} left unlinked",
                externalId, archiveId);
        }
    }

    /** Links an archived attempt to the submission id the platform gave it. */
    public void attachExternalId(String archiveId, Long externalId) {
        if (archiveId == null) return;
        try {
            repo.findById(archiveId).ifPresent(row -> {
                row.setExternalId(externalId);
                row.setVerdict("TESTING");
                row.setUpdatedAt(Instant.now());
                repo.save(row);
            });
        } catch (Exception e) {
            log.warn("Could not attach submission id {} to archive row {}: {}",
                externalId, archiveId, e.getMessage());
        }
    }

    /**
     * Marks an attempt as having been refused, so it is not left looking like it is still in
     * flight. The source stays — that is the copy the user needs to get their work back.
     */
    public void markRejected(String archiveId, String reason) {
        if (archiveId == null) return;
        try {
            repo.findById(archiveId).ifPresent(row -> {
                row.setVerdict("NOT_SUBMITTED");
                row.setUpdatedAt(Instant.now());
                repo.save(row);
            });
            log.debug("Archived attempt {} kept after a refused submission: {}",
                archiveId, reason);
        } catch (Exception e) {
            log.warn("Could not mark archive row {} rejected: {}", archiveId, e.getMessage());
        }
    }

    /** Keeps the archived copy's verdict in step with the platform's, when it has moved on. */
    public void updateVerdict(Long userId, String platform, Long externalId, String verdict,
                              Integer passedTestCount, Integer timeMs, Long memoryBytes) {
        if (externalId == null || verdict == null) return;
        try {
            repo.findByUserIdAndPlatformAndExternalId(userId, platform, externalId)
                .filter(row -> !verdict.equals(row.getVerdict()))
                .ifPresent(row -> {
                    row.setVerdict(verdict);
                    row.setPassedTestCount(passedTestCount);
                    row.setTimeConsumedMs(timeMs);
                    row.setMemoryConsumedBytes(memoryBytes);
                    row.setUpdatedAt(Instant.now());
                    repo.save(row);
                });
        } catch (Exception e) {
            log.debug("Could not update archived verdict for {}: {}", externalId, e.getMessage());
        }
    }

    /**
     * Brings a whole contest's archived verdicts up to date from one platform listing.
     *
     * Called from the submissions poll, which runs every few seconds while anything is
     * judging — so this is one query for the contest and a write only for rows that actually
     * moved, rather than a lookup per submission per tick.
     */
    public void syncVerdicts(Long userId, String platform, String contestId,
                             List<CfModels.Submission> remote) {
        if (contestId == null || remote == null || remote.isEmpty()) return;
        try {
            Map<Long, CodeSubmission> archived = new HashMap<>();
            for (CodeSubmission row : repo.findByUserIdAndPlatformAndContestId(
                    userId, platform, contestId)) {
                if (row.getExternalId() != null) archived.put(row.getExternalId(), row);
            }
            if (archived.isEmpty()) return;

            for (CfModels.Submission s : remote) {
                CodeSubmission row = s.getId() == null ? null : archived.get(s.getId());
                if (row != null) syncFromRemote(row, s);
            }
        } catch (Exception e) {
            log.debug("Verdict sync for contest {} failed: {}", contestId, e.getMessage());
        }
    }

    // -------------------------------------------------------------- read path

    /**
     * Every attempt at one problem, newest first, from both sources.
     *
     * The archive alone is answered without touching the network. Codeforces is asked as well
     * when a session exists, and a failure there is reported rather than thrown: an offline
     * user still gets everything CPIntel submitted for them, which is the case the lockdown
     * scenario actually depends on.
     *
     * <p>On a self-hosted judge there is no second source. DOMjudge publishes no page this can
     * re-read a submission's source from, so the archive is the whole answer — which is
     * reported as such rather than as an unreachable platform, because nothing is wrong and
     * there is nothing for the contestant to go and connect.
     */
    public ArchiveDto.AttemptPage attemptsForProblem(Long userId, String platform,
                                                     String contestId, String index) {
        String problemIndex = index.toUpperCase(Locale.ROOT);

        List<CodeSubmission> archived = repo
            .findByUserIdAndPlatformAndContestIdAndProblemIndexOrderBySubmittedAtDesc(
                userId, platform, contestId, problemIndex);

        Map<Long, CodeSubmission> byExternalId = new HashMap<>();
        for (CodeSubmission row : archived) {
            if (row.getExternalId() != null) byExternalId.put(row.getExternalId(), row);
        }

        List<ArchiveDto.Attempt> out = new ArrayList<>();
        boolean reachable = false;
        String notice = null;

        if (!CODEFORCES.equals(platform)) {
            for (CodeSubmission row : archived) out.add(toAttempt(row, contestId));
            out.sort(Comparator.comparing(ArchiveDto.Attempt::submittedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
            return new ArchiveDto.AttemptPage(markUnchanged(out), true,
                "Everything CPIntel sent to the judge for you is here.");
        }

        int cfContestId;
        try {
            cfContestId = Integer.parseInt(contestId);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("'" + contestId + "' is not a Codeforces contest id.");
        }

        CfSessionStore.StoredSession session = sessionStore.find(userId);
        if (session == null) {
            notice = "Connect your Codeforces account to also see submissions you made "
                + "outside CPIntel.";
        } else {
            List<CfModels.Submission> remote = remoteAttempts(cfContestId, problemIndex, session);
            reachable = remote != null;
            if (remote == null) {
                notice = "Codeforces could not be reached, so this is only what CPIntel has "
                    + "stored locally.";
            } else {
                for (CfModels.Submission s : remote) {
                    CodeSubmission local = s.getId() == null ? null : byExternalId.remove(s.getId());
                    if (local != null) {
                        // The platform is the authority on verdicts; keep the local copy in
                        // step so it stays accurate to read back offline later.
                        syncFromRemote(local, s);
                        out.add(toAttempt(local, contestId));
                    } else {
                        out.add(remoteOnlyAttempt(s, cfContestId, problemIndex));
                    }
                }
            }
        }

        // Whatever is left is CPIntel-only: attempts Codeforces never accepted, or rows it
        // did not return. Either way the source is here and worth showing.
        for (CodeSubmission row : byExternalId.values()) out.add(toAttempt(row, contestId));
        for (CodeSubmission row : archived) {
            if (row.getExternalId() == null) out.add(toAttempt(row, contestId));
        }

        out.sort(Comparator.comparing(ArchiveDto.Attempt::submittedAt,
            Comparator.nullsLast(Comparator.reverseOrder())));

        return new ArchiveDto.AttemptPage(markUnchanged(out), reachable, notice);
    }

    /** Everything archived for this user, newest first — the "reuse an old solution" view. */
    public List<ArchiveDto.Attempt> recent(Long userId, int limit) {
        int capped = Math.min(Math.max(limit, 1), RECENT_LIMIT);
        // Not passed through markUnchanged: consecutive rows here are different problems, so
        // "same as the one below" would be comparing unrelated solutions.
        return repo.findByUserIdOrderBySubmittedAtDesc(userId, PageRequest.of(0, capped))
            .stream()
            .map(row -> toAttempt(row, row.getContestId()))
            .toList();
    }

    /** An archived source. Local only — never touches the network. */
    public ArchiveDto.Source source(Long userId, String archiveId) {
        CodeSubmission row = repo.findById(archiveId)
            .filter(r -> r.getUserId() != null && r.getUserId().equals(userId))
            .orElseThrow(() -> ApiException.notFound("No archived submission with that id."));

        return new ArchiveDto.Source(
            row.getId(), row.getExternalId(), row.getPlatform(), row.getContestId(),
            row.getProblemIndex(), row.getProblemName(), row.getLanguageId(),
            row.getLanguageLabel(), row.getVerdict(), row.getSubmittedAt(),
            row.getSource(), "ARCHIVE");
    }

    /**
     * The source of a Codeforces submission, archiving it on the way through so the next read
     * is local. Returns the archived copy immediately when there already is one.
     */
    public ArchiveDto.Source codeforcesSource(Long userId, int contestId, long submissionId) {
        Optional<CodeSubmission> existing = repo
            .findByUserIdAndPlatformAndExternalId(userId, CODEFORCES, submissionId)
            .filter(r -> r.getSource() != null && !r.getSource().isBlank());
        if (existing.isPresent()) return source(userId, existing.get().getId());

        CfSessionStore.StoredSession session = sessionStore.find(userId);
        if (session == null) {
            throw ApiException.badRequest(
                "Connect your Codeforces account to read code you submitted outside CPIntel.");
        }

        CfWebSubmitClient.SubmissionDetail detail;
        try {
            detail = submitClient.fetchSubmission(session.cookieHeader(), session.userAgent(), contestId,
                submissionId);
        } catch (ApiException e) {
            if ("CF_SESSION_INVALID".equals(e.getCode())) sessionStore.delete(userId);
            throw e;
        }
        String code = detail.source();

        // One metadata lookup so the cached row is complete — verdict, problem name and
        // compiler — rather than a bare blob of code with no context next to it.
        List<CfModels.Submission> remote = remoteAttempts(contestId, null, session);
        CfModels.Submission meta = remote == null ? null : remote.stream()
            .filter(s -> s.getId() != null && s.getId() == submissionId)
            .findFirst().orElse(null);

        Instant now = Instant.now();
        CodeSubmission row = CodeSubmission.builder()
            .userId(userId)
            .platform(CODEFORCES)
            .externalId(submissionId)
            .contestId(String.valueOf(contestId))
            .problemIndex(meta == null || meta.getProblem() == null
                ? null : meta.getProblem().getIndex())
            .problemName(meta == null || meta.getProblem() == null
                ? null : meta.getProblem().getName())
            .languageLabel(meta == null ? null : meta.getProgrammingLanguage())
            .source(code)
            .sourceBytes(code.getBytes(StandardCharsets.UTF_8).length)
            .sourceHash(sha256(code))
            .verdict(meta == null ? null : meta.getVerdict())
            .passedTestCount(meta == null ? null : meta.getPassedTestCount())
            .timeConsumedMs(meta == null ? null : meta.getTimeConsumedMillis())
            .memoryConsumedBytes(meta == null ? null : meta.getMemoryConsumedBytes())
            .tests(toEntityTests(detail.tests()))
            .testCount(detail.testCount())
            .compilationError(detail.compilationError())
            .origin(CodeSubmission.Origin.FETCHED.name())
            .submittedAt(meta == null || meta.getCreationTimeSeconds() == null
                ? now : Instant.ofEpochSecond(meta.getCreationTimeSeconds()))
            .updatedAt(now)
            .build();

        String id = null;
        try {
            id = repo.save(row).getId();
        } catch (Exception e) {
            // Caching failed; the user still gets their code. Only the next read costs a
            // second fetch.
            log.warn("Could not cache fetched source for submission {}: {}",
                submissionId, e.getMessage());
        }

        return new ArchiveDto.Source(id, submissionId, CODEFORCES, String.valueOf(contestId),
            row.getProblemIndex(), row.getProblemName(), null, row.getLanguageLabel(),
            row.getVerdict(), row.getSubmittedAt(), code, "CODEFORCES");
    }

    /**
     * What the judge actually did, test by test.
     *
     * Deliberately a separate call from {@link #source}, not a field on it. Reading back your
     * own code must stay a local, instant, offline-safe operation; the test data may need a
     * round trip to Codeforces. Folding them together would make every "show me my old code"
     * wait on the network for something the user may not have asked to see.
     *
     * Fetched once and then archived, so the second look is local like everything else.
     */
    public ArchiveDto.TestReport testReport(Long userId, String archiveId) {
        CodeSubmission row = repo.findById(archiveId)
            .filter(r -> r.getUserId() != null && r.getUserId().equals(userId))
            .orElseThrow(() -> ApiException.notFound("No archived submission with that id."));

        if (row.getTests() != null) return toReport(row);

        if (row.getExternalId() == null) {
            return new ArchiveDto.TestReport(false, List.of(), null, null, null,
                row.getVerdict(),
                "Codeforces never accepted this attempt, so it was never judged. The code is "
                    + "still here.");
        }
        if (row.getContestId() == null) {
            return new ArchiveDto.TestReport(false, List.of(), null, failedOnTest(row), null,
                row.getVerdict(), "This submission has no contest on file to look it up by.");
        }

        CfSessionStore.StoredSession session = sessionStore.find(userId);
        if (session == null) {
            return new ArchiveDto.TestReport(false, List.of(), null, failedOnTest(row), null,
                row.getVerdict(),
                "Connect your Codeforces account to see what the judge ran.");
        }

        CfWebSubmitClient.SubmissionDetail detail;
        try {
            // This path is Codeforces-only — it re-reads a submission page — so the stored
            // text id is parsed back to the number Codeforces uses.
            detail = submitClient.fetchSubmission(session.cookieHeader(), session.userAgent(),
                Integer.parseInt(row.getContestId()), row.getExternalId());
        } catch (ApiException e) {
            if ("CF_SESSION_INVALID".equals(e.getCode())) sessionStore.delete(userId);
            return new ArchiveDto.TestReport(false, List.of(), null, failedOnTest(row), null,
                row.getVerdict(), e.getMessage());
        }

        row.setTests(toEntityTests(detail.tests()));
        row.setTestCount(detail.testCount());
        row.setCompilationError(detail.compilationError());
        row.setUpdatedAt(Instant.now());
        try {
            repo.save(row);
        } catch (Exception e) {
            log.warn("Could not archive test data for {}: {}", archiveId, e.getMessage());
        }
        return toReport(row);
    }

    private ArchiveDto.TestReport toReport(CodeSubmission row) {
        List<CodeSubmission.TestOutcome> stored =
            row.getTests() == null ? List.of() : row.getTests();

        List<ArchiveDto.TestOutcome> tests = stored.stream()
            .map(t -> new ArchiveDto.TestOutcome(
                t.getIndex(), t.getVerdict(), t.getInput(), t.getOutput(), t.getAnswer(),
                t.getCheckerMessage(), t.getExitCode(), t.getTimeMs(), t.getMemoryBytes(),
                Boolean.TRUE.equals(t.getTruncated())))
            .toList();

        String notice = null;
        if (tests.isEmpty() && row.getCompilationError() == null) {
            // Codeforces answered, and had nothing to show. During a live round it names the
            // failing test and withholds everything behind it, which is the usual reason.
            notice = failedOnTest(row) != null
                ? "Codeforces does not reveal test data while a contest is running — it will "
                    + "once the round is over."
                : "Codeforces returned no test data for this submission.";
        }

        return new ArchiveDto.TestReport(
            !tests.isEmpty() || row.getCompilationError() != null,
            tests, row.getTestCount(), failedOnTest(row), row.getCompilationError(),
            row.getVerdict(), notice);
    }

    /**
     * Which test broke, 1-based.
     *
     * Taken from the API's passedTestCount rather than parsed out of the verdict markup —
     * "Wrong answer on test 2" is a rendered string that exists to be read by people.
     */
    private static Integer failedOnTest(CodeSubmission row) {
        if (row.getPassedTestCount() == null) return null;
        String verdict = row.getVerdict();
        if (verdict == null || "OK".equals(verdict)) return null;
        return row.getPassedTestCount() + 1;
    }

    private static List<CodeSubmission.TestOutcome> toEntityTests(
            List<CfWebSubmitClient.CfTest> tests) {
        if (tests == null) return List.of();
        return tests.stream()
            .map(t -> CodeSubmission.TestOutcome.builder()
                .index(t.index()).verdict(t.verdict())
                .input(t.input()).output(t.output()).answer(t.answer())
                .checkerMessage(t.checkerMessage()).exitCode(t.exitCode())
                .timeMs(t.timeMs()).memoryBytes(t.memoryBytes())
                .truncated(t.truncated())
                .build())
            .toList();
    }

    // ------------------------------------------------------------- internals

    /**
     * This user's Codeforces submissions for one problem, or null when Codeforces could not be
     * asked. Null and empty mean different things here — nothing submitted versus no answer —
     * and the UI says something different for each.
     */
    private List<CfModels.Submission> remoteAttempts(int contestId, String problemIndex,
                                                     CfSessionStore.StoredSession session) {
        try {
            CfSubmissionsResponse resp =
                codeforcesClient.getContestStatus(contestId, session.handle());
            if (resp == null || resp.getResult() == null) return null;
            return resp.getResult().stream()
                .filter(s -> problemIndex == null
                    || (s.getProblem() != null
                        && problemIndex.equalsIgnoreCase(s.getProblem().getIndex())))
                .toList();
        } catch (Exception e) {
            log.debug("Codeforces submission list for contest {} failed: {}",
                contestId, e.getMessage());
            return null;
        }
    }

    private void syncFromRemote(CodeSubmission local, CfModels.Submission remote) {
        boolean changed = false;
        if (remote.getVerdict() != null && !remote.getVerdict().equals(local.getVerdict())) {
            local.setVerdict(remote.getVerdict());
            local.setPassedTestCount(remote.getPassedTestCount());
            local.setTimeConsumedMs(remote.getTimeConsumedMillis());
            local.setMemoryConsumedBytes(remote.getMemoryConsumedBytes());
            changed = true;
        }
        if (local.getProblemName() == null && remote.getProblem() != null) {
            local.setProblemName(remote.getProblem().getName());
            changed = true;
        }
        if (local.getLanguageLabel() == null && remote.getProgrammingLanguage() != null) {
            local.setLanguageLabel(remote.getProgrammingLanguage());
            changed = true;
        }
        if (!changed) return;
        try {
            local.setUpdatedAt(Instant.now());
            repo.save(local);
        } catch (Exception e) {
            log.debug("Could not refresh archived row {}: {}", local.getId(), e.getMessage());
        }
    }

    private ArchiveDto.Attempt toAttempt(CodeSubmission row, String contestId) {
        boolean stored = row.getSource() != null && !row.getSource().isBlank();
        return new ArchiveDto.Attempt(
            row.getId(), row.getExternalId(), row.getPlatform(),
            row.getContestId() == null ? contestId : row.getContestId(),
            row.getProblemIndex(), row.getProblemName(),
            row.getLanguageId(), row.getLanguageLabel(),
            row.getVerdict(), row.getPassedTestCount(), row.getTimeConsumedMs(),
            row.getMemoryConsumedBytes(), row.getSubmittedAt(),
            row.getSourceBytes(), row.getSourceHash(), stored, false,
            submissionUrl(row.getContestId() == null ? contestId : row.getContestId(),
                row.getExternalId()));
    }

    private ArchiveDto.Attempt remoteOnlyAttempt(CfModels.Submission s, int contestId,
                                                 String problemIndex) {
        return new ArchiveDto.Attempt(
            null, s.getId(), CODEFORCES, String.valueOf(contestId),
            s.getProblem() == null ? problemIndex : s.getProblem().getIndex(),
            s.getProblem() == null ? null : s.getProblem().getName(),
            null, s.getProgrammingLanguage(),
            s.getVerdict() == null ? "TESTING" : s.getVerdict(),
            s.getPassedTestCount(), s.getTimeConsumedMillis(), s.getMemoryConsumedBytes(),
            s.getCreationTimeSeconds() == null
                ? null : Instant.ofEpochSecond(s.getCreationTimeSeconds()),
            null, null, false, false,
            submissionUrl(String.valueOf(contestId), s.getId()));
    }

    /**
     * Flags each attempt whose source is byte-identical to the one below it in the list, so a
     * run of near-duplicate submissions is skimmable. Only attempts whose source is actually
     * held can be compared; a hash we do not have is not a match.
     */
    private List<ArchiveDto.Attempt> markUnchanged(List<ArchiveDto.Attempt> attempts) {
        List<ArchiveDto.Attempt> out = new ArrayList<>(attempts.size());
        for (int i = 0; i < attempts.size(); i++) {
            ArchiveDto.Attempt a = attempts.get(i);
            ArchiveDto.Attempt older = i + 1 < attempts.size() ? attempts.get(i + 1) : null;
            boolean same = a.sourceHash() != null && older != null
                && a.sourceHash().equals(older.sourceHash());
            out.add(same
                ? new ArchiveDto.Attempt(a.id(), a.externalId(), a.platform(), a.contestId(),
                    a.problemIndex(), a.problemName(), a.languageId(), a.languageLabel(),
                    a.verdict(), a.passedTestCount(), a.timeConsumedMillis(),
                    a.memoryConsumedBytes(), a.submittedAt(), a.sourceBytes(), a.sourceHash(),
                    a.stored(), true, a.url())
                : a);
        }
        return out;
    }

    private static String submissionUrl(String contestId, Long submissionId) {
        if (contestId == null || submissionId == null) return null;
        return "https://codeforces.com/contest/" + contestId + "/submission/" + submissionId;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }
}
