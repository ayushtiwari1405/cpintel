package com.cpintel.roadmap;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** What the placement gauntlet sends and receives. Answers never leave the server unasked. */
public final class GauntletDto {

    private GauntletDto() {}

    /** A question as served: options already shuffled, the right one not marked. */
    public record QuestionView(String id, int tier, String prompt, String code,
                               List<String> options) {}

    public record SectionView(String id, String title, String blurb,
                              List<QuestionView> questions) {}

    /**
     * The whole gauntlet, plus the last result so the page can offer a retake, and when the
     * next attempt opens (null: now).
     */
    public record Paper(List<SectionView> sections, List<Integer> tierRatings,
                        ResultView lastResult, Instant nextAttemptAt) {}

    /** A fresh attempt; every check and the submit name it. */
    public record Started(String attemptId) {}

    /**
     * One tier's answers: question id → the index of the option picked, as served. -1 means
     * "not sure yet".
     */
    public record CheckRequest(@NotNull String attemptId, @NotNull Map<String, Integer> answers) {}

    public record SubmitRequest(@NotNull String attemptId) {}

    /** One answered question, marked — returned after each tier so the climb can go on. */
    public record Checked(String id, boolean correct, int correctIndex, String explanation) {}

    public record SectionResult(String section, String title, int tiersPassed, int rating) {}

    public record ResultView(int overallRating, List<SectionResult> sections, int nodesPlaced,
                             Instant takenAt) {}
}
