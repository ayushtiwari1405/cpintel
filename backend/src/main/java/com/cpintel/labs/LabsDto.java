package com.cpintel.labs;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** What the lab endpoints take and give. */
public final class LabsDto {

    private LabsDto() {}

    /** A problem as the admin entered it: a link (or shorthand) and its marks. */
    public record ProblemInput(
        @NotBlank @Size(max = 500) String link,
        @DecimalMin("0") @DecimalMax("1000") BigDecimal marks) {}

    public record LabRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 4000) String description,
        @NotNull Instant opensAt,
        @NotNull Instant dueAt,
        boolean countEarlierSolves,
        @Size(max = 100) List<Long> groupIds,
        @NotEmpty(message = "Add at least one problem") @Size(max = 60) List<@Valid ProblemInput> problems) {}

    public record ResolveRequest(@NotBlank @Size(max = 500) String link) {}

    public record ProblemView(Long problemId, String platform, String key, String title, String url,
                              String difficulty, BigDecimal marks) {}

    /** UPCOMING before it opens, OPEN until the deadline, CLOSED after. */
    public record LabSummary(Long labId, Long classroomId, String title, String description,
                             Instant opensAt, Instant dueAt, boolean countEarlierSolves,
                             List<Long> groupIds, List<String> groupNames, Instant lastCheckedAt,
                             List<ProblemView> problems, BigDecimal totalMarks, int studentCount,
                             String state) {}

    /** SOLVED, LATE, or null when not solved. */
    public record Cell(Long problemId, String status, Instant solvedAt) {}

    public record StudentRow(Long userId, String username, String fullName,
                             String codeforcesHandle, boolean codeforcesVerified,
                             String leetcodeHandle, boolean leetcodeVerified,
                             List<Cell> cells, int solved, int late, BigDecimal marks) {}

    public record Progress(LabSummary lab, List<StudentRow> students) {}

    public record MyProblem(ProblemView problem, String status, Instant solvedAt) {}

    public record MyLab(Long labId, Long classroomId, String classroomName, String title,
                        String description, Instant opensAt, Instant dueAt,
                        boolean countEarlierSolves, Instant lastCheckedAt, String state,
                        List<MyProblem> problems, BigDecimal marks, BigDecimal totalMarks) {}

    /** A linked handle, or null fields when none is linked. */
    public record Handle(String handle, boolean verified) {}

    public record MyLabs(List<MyLab> labs, Handle codeforces, Handle leetcode) {}

    public record CheckResult(int recorded, Instant checkedAt) {}

    public record CheckStarted(boolean started, String message) {}
}
