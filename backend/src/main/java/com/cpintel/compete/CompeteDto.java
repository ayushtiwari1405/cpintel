package com.cpintel.compete;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * DTOs for the compete arena — running a real contest from one page.
 *
 * Nothing here drives the contest itself. The app never registers, never withdraws and never
 * touches rating; it reads the judge's state and forwards submissions. Whether a round counts
 * is decided entirely by the judge.
 *
 * <p>Contest and submission ids are strings rather than numbers. Codeforces numbers both and
 * DOMjudge numbers neither — a DOMjudge contest is as likely to be called {@code nwerc18} as
 * {@code 3} — so the arena carries them as opaque text and lets each provider decide what is
 * valid. Making these {@code int} was the single assumption that had to be unpicked to run a
 * second judge at all.
 */
public class CompeteDto {

    /**
     * A problem statement as the judge published it: the bytes, and what they are.
     *
     * <p>{@code contentType} is whatever the judge served — {@code application/pdf} for most
     * DOMjudge problems, {@code text/plain} or {@code text/html} for ones whose package holds
     * a text statement instead. Carried rather than assumed, because assuming PDF is what made
     * text statements render as a blank pane.
     */
    public record StatementDocument(byte[] bytes, String contentType) {

        /** True when this is a PDF, which is the only type the arena embeds rather than renders. */
        public boolean isPdf() {
            return contentType != null
                && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/pdf");
        }
    }

    /** Platform selector. CODEFORCES and DOMJUDGE are wired up; CODECHEF is the next one in. */
    public enum Platform { CODEFORCES, CODECHEF, DOMJUDGE }

    /**
     * How a judge publishes its statements.
     *
     * Codeforces renders HTML the arena can style to match itself; DOMjudge attaches the
     * problem-package PDF. The page needs to know which before it can lay out the statement
     * pane, and guessing from whether a field is empty would render a blank panel on every
     * problem that genuinely has no statement.
     */
    public enum StatementFormat { HTML, PDF }

    public record LoadRequest(
        Platform platform,
        @NotBlank @Size(max = 500) String url
    ) {}

    public record ContestProblem(
        String index,
        String name,
        Double points,
        Integer rating
    ) {}

    /**
     * Everything the page needs to decide what to render: whether the contest has started,
     * how long is left, and whether Codeforces will take submissions from this account.
     */
    public record ContestInfo(
        String id,
        String name,
        String platform,
        /** BEFORE, CODING, PENDING_SYSTEM_TEST, SYSTEM_TEST or FINISHED. */
        String phase,
        boolean running,
        boolean frozen,
        Instant startsAt,
        long durationSeconds,
        /** Negative once the contest has begun. */
        long secondsUntilStart,
        /** Zero once it has ended. */
        long secondsRemaining,
        boolean submissionsOpen,
        String submissionsClosedReason,
        /** Whether this contest lets the user open their own uploaded files. Admin-controlled. */
        boolean personalFilesEnabled,
        /** HTML for Codeforces, PDF for DOMjudge — decides how the statement pane renders. */
        StatementFormat statementFormat,
        List<ContestProblem> problems,
        String url
    ) {}

    public record ContestSubmitRequest(
        @NotBlank @Size(max = 8) String index,
        @NotBlank String languageId,
        @NotBlank @Size(max = 65_536) String source
    ) {}

    /** One row of the in-contest submission list. */
    public record ContestSubmission(
        String id,
        String index,
        String problemName,
        String language,
        String verdict,
        Integer passedTestCount,
        Integer timeConsumedMillis,
        Long memoryConsumedBytes,
        Instant createdAt,
        boolean finished,
        String url
    ) {}

    /**
     * One problem's state for one team, as the judge's scoreboard reports it.
     *
     * {@code minute} is minutes from the contest start at which the problem was solved, and is
     * null when it has not been. Attempts are counted whether or not they succeeded, because
     * that is what the penalty is computed from and what a contestant is comparing against.
     */
    public record LeaderboardCell(
        String index,
        boolean solved,
        int attempts,
        Integer minute
    ) {}

    /** One team's row on the board. */
    public record LeaderboardRow(
        Integer rank,
        String teamId,
        String teamName,
        int solved,
        Integer penalty,
        /** True for the viewer's own team, so the page can pin and highlight it. */
        boolean mine,
        List<LeaderboardCell> problems
    ) {}

    /**
     * The contest's full board, plus the viewer's own team pulled out of it.
     *
     * <p>{@code frozen} and {@code live} are two different things and the page must say both.
     * {@code frozen} means the contest has entered its freeze, so the board is deliberately
     * not showing the last hour — normal, and every contestant expects it. {@code live} is
     * false when CPIntel could not read the judge's own view at all and fell back to the
     * public one, which is a property of the credentials rather than of the contest. Showing a
     * frozen board as though it were current is the specific failure worth avoiding: it looks
     * exactly like a room where nobody is solving anything.
     */
    public record Leaderboard(
        List<LeaderboardRow> rows,
        String myTeamId,
        String myTeamName,
        /** The viewer's own row, repeated here so the page need not search for it. */
        LeaderboardRow myTeam,
        /** The problem labels, in board order, for the column headers. */
        List<String> problemIndexes,
        boolean frozen,
        boolean live,
        Instant fetchedAt
    ) {}

    /**
     * Live standing for the signed-in handle. Refreshed when a verdict lands or on a slow
     * timer — not continuously, because standings is an expensive call and Codeforces
     * rate-limits it.
     */
    public record RankInfo(
        Integer rank,
        Double points,
        Integer penalty,
        Integer solvedCount,
        boolean frozen,
        boolean participating,
        Instant fetchedAt
    ) {}
}
