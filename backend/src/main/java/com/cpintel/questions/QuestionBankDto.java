package com.cpintel.questions;

import com.cpintel.entity.BankQuestion;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public class QuestionBankDto {

    private QuestionBankDto() {}

    /** A classroom whose bank this person may open, and why. */
    public record BankClassroom(Long classroomId, String name, Role role, boolean active) {}

    public enum Role { ADMIN, TA }

    public record QuestionSummary(
        Long questionId,
        Long classroomId,
        String title,
        BankQuestion.Status status,
        int samples,
        int secretTests,
        long testBytes,
        int version,
        boolean archived,
        Instant updatedAt,
        String updatedBy
    ) {}

    public record QuestionDetail(
        Long questionId,
        Long classroomId,
        String title,
        String statement,
        int timeLimitMs,
        int memoryLimitMb,
        BankQuestion.Checker checker,
        Double floatTolerance,
        String modelLanguage,
        String modelSource,
        BankQuestion.Status status,
        int version,
        boolean archived,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        String updatedBy,
        List<TestInfo> tests,
        Limits limits
    ) {}

    public record TestInfo(
        Long testId,
        boolean sample,
        int ordinal,
        String name,
        int inputBytes,
        Integer answerBytes
    ) {}

    /** What the bank accepts, so the page can say so before an upload is refused. */
    public record Limits(int maxTests, long maxQuestionBytes, int maxFileBytes, int maxSampleBytes) {}

    /**
     * A new question, or a save of an existing one. {@code version} is the version the editor
     * loaded; a save is refused if the question has moved on since.
     */
    public record QuestionRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 100_000) String statement,
        @NotNull @Min(100) @Max(20_000) Integer timeLimitMs,
        @NotNull @Min(16) @Max(2048) Integer memoryLimitMb,
        @NotNull BankQuestion.Checker checker,
        @Positive Double floatTolerance,
        @Pattern(regexp = "c|cpp|python3") String modelLanguage,
        @Size(max = 200_000) String modelSource,
        Integer version
    ) {}

    /** The new order of a question's samples, or of its secret tests: every one, once. */
    public record TestOrderRequest(
        @NotNull Boolean sample,
        @NotNull @Size(max = 500) List<Long> testIds,
        @NotNull Integer version
    ) {}

    public record Download(String name, byte[] bytes) {}
}
