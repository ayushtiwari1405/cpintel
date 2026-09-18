package com.cpintel.entity.mongo;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.List;

/**
 * One piece of source the user submitted, kept so they never have to leave the app to read
 * their own code back.
 *
 * This is deliberately separate from {@link CfSubmission}, which is the analytics mirror of a
 * Codeforces submission — metadata only, synced in bulk, and safe to wipe and re-derive. This
 * collection holds the thing that cannot be re-derived: the source itself. Losing a row here
 * loses work; losing a row there costs one API call.
 *
 * It is also platform-agnostic on purpose. Codeforces will hand the source back if asked
 * nicely enough, so for CF this is a cache. For a contest CPIntel runs itself through
 * DOMjudge there is nobody to ask — this collection is the only copy, which is why the write
 * happens before the submission leaves the building rather than after it succeeds.
 */
@Document(collection = "code_submissions")
@CompoundIndexes({
    // The panel's main query: everything I ever sent to this problem, newest first.
    @CompoundIndex(name = "idx_code_user_problem",
        def = "{'userId': 1, 'platform': 1, 'contestId': 1, 'problemIndex': 1, 'submittedAt': -1}"),
    // Used to answer "is this Codeforces submission already archived" before fetching it.
    @CompoundIndex(name = "idx_code_user_external",
        def = "{'userId': 1, 'platform': 1, 'externalId': 1}")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CodeSubmission {

    /** How the row got here. */
    public enum Origin {
        /** Written by CPIntel as the code was sent. The only copy that is guaranteed to exist. */
        SUBMITTED,
        /** Pulled back from the platform for a submission made outside CPIntel. */
        FETCHED
    }

    @Id
    private String id;

    @Indexed
    @Field("userId")
    private Long userId;

    /** CODEFORCES today; DOMJUDGE once admin-run contests land. */
    @Field("platform")
    private String platform;

    /**
     * The platform's own submission id, once it has one.
     *
     * Null between the archive write and the platform's answer — and permanently null if the
     * submission was refused, which is exactly the case where having the source still matters.
     */
    @Field("externalId")
    private Long externalId;

    /**
     * The judge's own contest id, as text.
     *
     * A string because DOMjudge does not number its contests — {@code nwerc18} is an ordinary
     * id there — while Codeforces does. Storing the number Codeforces uses as text costs
     * nothing and is the only shape that holds both.
     */
    @Field("contestId")
    private String contestId;

    @Field("problemIndex")
    private String problemIndex;

    @Field("problemName")
    private String problemName;

    /** The platform's language id (Codeforces programTypeId), for resubmitting as-was. */
    @Field("languageId")
    private String languageId;

    /** Human-readable compiler name, e.g. "GNU G++20 13.2 (64 bit, winlibs)". */
    @Field("languageLabel")
    private String languageLabel;

    @Field("source")
    private String source;

    @Field("sourceBytes")
    private Integer sourceBytes;

    /** SHA-256 of the source, so the UI can mark an attempt as unchanged from the last one. */
    @Field("sourceHash")
    private String sourceHash;

    @Field("verdict")
    private String verdict;

    @Field("passedTestCount")
    private Integer passedTestCount;

    @Field("timeConsumedMs")
    private Integer timeConsumedMs;

    @Field("memoryConsumedBytes")
    private Long memoryConsumedBytes;

    @Field("origin")
    private String origin;

    /**
     * What the judge did with it, test by test — kept for the same reason the source is:
     * during a locked-down contest the submission page is not somewhere the user can go.
     *
     * Null means never fetched; empty means fetched and the platform gave nothing, which is
     * what a running contest looks like. The two are not the same and the UI says so.
     */
    @Field("tests")
    private List<TestOutcome> tests;

    /** Total tests the platform reported, even when it withheld the data for them. */
    @Field("testCount")
    private Integer testCount;

    @Field("compilationError")
    private String compilationError;

    @Indexed
    @Field("submittedAt")
    private Instant submittedAt;

    @Field("updatedAt")
    private Instant updatedAt;

    /**
     * One test. Every field except the index is optional: Codeforces reveals the data for
     * practice submissions but only the verdict during a live round.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TestOutcome {
        private Integer index;
        private String verdict;
        private String input;
        private String output;
        private String answer;
        /** The checker's own words, e.g. "wrong answer Test 138: answer is not maximised". */
        private String checkerMessage;
        private Integer exitCode;
        private Integer timeMs;
        private Long memoryBytes;
        /** Codeforces clipped at least one of the three payloads. */
        private Boolean truncated;
    }
}
