package com.cpintel.archive;

import java.time.Instant;
import java.util.List;

/**
 * DTOs for the code archive — reading your own previous submissions without leaving the app.
 */
public class ArchiveDto {

    /**
     * One past attempt at a problem.
     *
     * A row can come from either side of the merge, which is what {@code stored} is for:
     * true means the source is already in CPIntel and opening it is instant and works
     * offline, false means only Codeforces has it and reading it costs a request.
     */
    public record Attempt(
        /** Archive id. Null when only the platform knows about this submission. */
        String id,
        /** The platform's submission id. Null for an attempt the platform refused. */
        Long externalId,
        String platform,
        String contestId,
        String problemIndex,
        String problemName,
        String languageId,
        String languageLabel,
        String verdict,
        Integer passedTestCount,
        Integer timeConsumedMillis,
        Long memoryConsumedBytes,
        Instant submittedAt,
        Integer sourceBytes,
        String sourceHash,
        boolean stored,
        /** True when this attempt's source is byte-identical to the attempt after it. */
        boolean sameAsPrevious,
        String url
    ) {}

    /**
     * One test as the judge ran it.
     *
     * Everything but the index is nullable on purpose. Codeforces shows the data for practice
     * submissions and withholds it during a live round, so a row here can legitimately be
     * nothing more than "test 4, wrong answer" — which is still worth showing.
     */
    public record TestOutcome(
        Integer index,
        String verdict,
        String input,
        String output,
        String answer,
        String checkerMessage,
        Integer exitCode,
        Integer timeMs,
        Long memoryBytes,
        boolean truncated
    ) {}

    /**
     * What the judge did with a submission.
     *
     * {@code available} false with a non-null {@code failedOnTest} is the running-contest
     * case: the platform will say which test broke but not what was in it.
     */
    public record TestReport(
        boolean available,
        List<TestOutcome> tests,
        Integer testCount,
        /** 1-based index of the first failing test, when the platform names one. */
        Integer failedOnTest,
        String compilationError,
        String verdict,
        String notice
    ) {}

    /** A retrieved source, ready to drop back into the editor. */
    public record Source(
        String id,
        Long externalId,
        String platform,
        String contestId,
        String problemIndex,
        String problemName,
        String languageId,
        String languageLabel,
        String verdict,
        Instant submittedAt,
        String source,
        /** Where it came from: ARCHIVE (local, instant) or CODEFORCES (just fetched). */
        String retrievedFrom
    ) {}

    public record AttemptPage(List<Attempt> attempts, boolean codeforcesReachable,
                              String notice) {}
}
