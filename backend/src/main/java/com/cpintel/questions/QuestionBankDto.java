package com.cpintel.questions;

import com.cpintel.entity.BankQuestion;
import com.cpintel.entity.BankQuestionCheck;
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
    /** {@code canEdit}: active, and an admin, or a TA whom the admins let add questions. */
    public record BankClassroom(Long classroomId, String name, Role role, boolean active, boolean canEdit) {}

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
        BankQuestion.StatementKind statementKind,
        StatementPdfInfo statementPdf,
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

    /** The setter's own statement PDF, when there is one; it may be kept while Markdown is used. */
    public record StatementPdfInfo(String fileName, int sizeBytes, int pages, Instant uploadedAt,
                                   String uploadedBy) {}

    /**
     * What the editor holds right now, rendered without saving: so the preview follows the
     * typing. The question's saved samples are added.
     */
    public record PreviewRequest(
        @Size(max = 200) String title,
        @Size(max = 100_000) String statement,
        @NotNull @Min(100) @Max(20_000) Integer timeLimitMs,
        @NotNull @Min(16) @Max(2048) Integer memoryLimitMb
    ) {}

    /** A question made from a package, and what the import had to change or leave out. */
    public record ImportResult(QuestionDetail question, List<String> warnings) {}

    /** Start a check; with {@code generateMissing}, missing answers come from the model solution first. */
    public record CheckRequest(boolean generateMissing) {}

    /** The latest check of a question's model solution. */
    public record Check(
        BankQuestionCheck.Status status,
        boolean generate,
        /** The question version the result is about; compare with the question's own. */
        int version,
        String startedBy,
        Instant startedAt,
        Instant finishedAt,
        String summary,
        Integer maxTimeMs,
        List<CheckedTest> results
    ) {}

    /** One test's outcome. {@code verdict} is a QuestionCheckService.Verdict name. */
    public record CheckedTest(Long testId, boolean sample, int ordinal, String verdict, Long timeMs, String message) {}

        /** A rendered statement, with any formula that could not be drawn. */
    public record Rendered(byte[] pdf, List<String> problems) {}
}
