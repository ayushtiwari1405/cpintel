package com.cpintel.compete;

import com.cpintel.exception.ApiException;
import com.cpintel.practice.PracticeDto;

import java.util.List;

/**
 * One judge, as the compete arena needs to see it.
 *
 * <p>The arena page is identical whichever judge is behind it — statement on the left, editor
 * and console on the right, submissions underneath, a clock and a rank in the header. What
 * differs is entirely in how those are obtained, and the two implementations could hardly be
 * less alike: Codeforces is a public service read by scraping pages and calling a rate-limited
 * API as the contestant, DOMjudge is a machine the operator owns, read through a documented
 * API as an admin acting on a team's behalf. Putting that difference behind one interface is
 * what stops it leaking into the controller, the page, and eventually the contest.
 *
 * <p><b>Contest ids are strings.</b> Codeforces numbers its contests and DOMjudge does not —
 * {@code nwerc18} is a perfectly ordinary DOMjudge contest id. The arena therefore treats an
 * id as opaque text and lets each provider decide what is valid, which is why
 * {@link #parseContestId} belongs here rather than in a shared parser.
 */
public interface CompeteProvider {

    /** CODEFORCES or DOMJUDGE — matched against {@link CompeteDto.Platform}. */
    String platform();

    /**
     * Turns whatever the user pasted into this judge's contest id.
     *
     * Accepts a link or a bare id, and throws a message naming the expected shape rather than
     * returning null — a mistyped contest link is the single most common thing to go wrong on
     * this page, and it happens while somebody is trying to start a round.
     */
    String parseContestId(String raw);

    /** Phase, clock, problems, and whether this contestant may submit right now. */
    CompeteDto.ContestInfo contestInfo(Long userId, String contestId);

    /** One problem's statement, by the label the contestant sees (A, B, C…). */
    PracticeDto.ProblemDetail statement(Long userId, String contestId, String index);

    /** The languages this contest will accept, for the editor's picker. */
    List<PracticeDto.LanguageOption> languages(Long userId, String contestId);

    /** Sends a solution to the judge as this contestant. */
    CompeteDto.ContestSubmission submit(Long userId, String contestId,
                                        CompeteDto.ContestSubmitRequest req);

    /** This contestant's own submissions in the contest, newest first. */
    List<CompeteDto.ContestSubmission> submissions(Long userId, String contestId);

    /** This contestant's live position, as far as the judge will say. */
    CompeteDto.RankInfo rank(Long userId, String contestId);

    /**
     * The contest's whole board, for judges that will publish one.
     *
     * Default-throws rather than returning an empty board, because the two mean different
     * things to the page: an empty board is a contest nobody has scored on yet, and this is a
     * judge that will not show one at all. Codeforces locks its standings endpoint down for
     * exactly the contests the arena runs, so {@link #rank} there is derived per contestant and
     * a full board is not available at any price — a caller must be able to tell that apart
     * from a quiet contest and hide the panel instead of rendering an empty table.
     */
    default CompeteDto.Leaderboard leaderboard(Long userId, String contestId) {
        throw ApiException.badRequest(
            platform() + " does not publish a full contest board to contestants.");
    }

    /**
     * One problem's statement as a document, for judges that publish a file instead of a page.
     *
     * <p>Only DOMjudge implements this. Codeforces renders statements as HTML and the arena
     * shows them inline, so asking it for a document is a programming error rather than a
     * missing feature — hence a thrown exception rather than a null that would render as a
     * blank pane.
     *
     * <p><b>The type travels with the bytes.</b> This used to return a bare {@code byte[]} and
     * the route above it declared {@code application/pdf}, which was true of most DOMjudge
     * statements and not all of them: a real seven-problem contest measured while fixing this
     * served four PDFs and three {@code text/plain} files. The three arrived at the browser
     * labelled as PDFs and rendered as an empty pane.
     */
    default CompeteDto.StatementDocument statementDocument(Long userId, String contestId,
                                                           String index) {
        throw ApiException.badRequest(
            platform() + " statements are read as HTML, not as a document.");
    }
}
