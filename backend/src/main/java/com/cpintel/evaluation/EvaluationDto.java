package com.cpintel.evaluation;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class EvaluationDto {

    private EvaluationDto() {}

    // ------------------------------------------------------------- classroom TAs

    public record Ta(Long userId, String username, String fullName, String email) {}

    /**
     * A TA to add. As with a roster row, an existing account is found by email, then by
     * username, and linked; otherwise a new one is created, which needs an email.
     */
    public record TaRequest(
        @Size(max = 255) String email,
        @Size(max = 50) String username,
        @Size(max = 100) String fullName
    ) {}

    /** {@code password} is set only when a new account was created, and is shown once. */
    public record TaAdded(Ta ta, boolean created, String password, String message) {}

    // --------------------------------------------------------------- assignments

    public record Assignment(
        Long assignmentId,
        Long taUserId,
        String taUsername,
        String taFullName,
        /** Null for every question. */
        String problemLabel,
        /** Null for "from the first student". */
        String rangeFrom,
        /** Null for "to the last student". */
        String rangeTo,
        /** Students on the roster the range covers now. */
        int studentCount,
        Instant createdAt
    ) {}

    public record AssignmentRequest(
        @NotNull Long taUserId,
        @Size(max = 16) String problemLabel,
        @Size(max = 50) String rangeFrom,
        @Size(max = 50) String rangeTo
    ) {}

    /** Everything the admin's evaluation tab needs to hand out work. */
    public record AssignmentBoard(
        String state,
        String stateMessage,
        List<Assignment> assignments,
        List<Ta> tas,
        List<String> problems,
        /** The roster's usernames in natural order, for choosing and previewing ranges. */
        List<String> students,
        /** Every TA in the classroom and how far they have got, for the live dashboard. */
        List<TaProgress> progress
    ) {}

    public record TaProgress(
        Long taUserId,
        String taUsername,
        String taFullName,
        /** Answers their assignments cover. */
        int cells,
        /** Of those, how many have a mark set by hand. */
        int marked,
        /** Answers an admin reopened that this TA covers, still waiting for them. */
        int reopened,
        /** Distinct answers that needed a change after they froze, in all. */
        int changesRequired,
        /** Of those, sent back to the TA. */
        int sentBack,
        /** Of those, changed by an admin over the TA's mark. */
        int changedByAdmin,
        /** When they froze their marking, or null while they are still at it. */
        Instant frozenAt
    ) {}

    // --------------------------------------------------------------------- sheet

    /** An examination a TA has work on. */
    public record TaExam(
        Long eventId,
        String name,
        String classroomName,
        Instant startsAt,
        Instant endsAt,
        String state,
        int cells,
        int marked,
        /** When this TA froze their marking here, or null. */
        Instant frozenAt
    ) {}

    public record Problem(String label, String title, double maxMarks) {}

    /** The one submission a marker reads for a student and question. */
    public record Submission(
        String id,
        String verdict,
        boolean accepted,
        Instant submittedAt,
        String languageLabel,
        Integer sourceBytes,
        /** Only on the single-submission route. */
        String source
    ) {}

    public record Cell(
        Long userId,
        String username,
        String fullName,
        String label,
        double maxMarks,
        /** What the judge's verdict alone earns: full marks once accepted, else nothing. */
        double autoMarks,
        /** The mark set by hand, or null when the judge's mark stands. */
        Double marks,
        String remark,
        String markedBy,
        Instant markedAt,
        /** The latest accepted submission, else the latest; null when nothing was sent. */
        Submission submission,
        int attempts,
        /** An admin reopened this answer for re-marking after the TA froze. */
        boolean reopened,
        /** Whether a TA who froze covers this answer (always true on a frozen TA's sheet). */
        boolean frozen,
        /** Whether the one reading the sheet can't change this mark now. */
        boolean locked
    ) {}

    public record Sheet(
        Long eventId,
        String name,
        /** NOT_ENDED, OPEN or DONE. Marks can be changed only while OPEN. */
        String state,
        String stateMessage,
        List<Problem> problems,
        List<Cell> cells,
        /** On a TA's sheet, when they froze their marking; null otherwise. */
        Instant frozenAt
    ) {}

    /** Reopen one answer for the TA who froze it, or close it again. */
    public record ReopenRequest(
        @NotNull Long userId,
        @NotBlank @Size(max = 16) String label,
        @NotNull Boolean reopen
    ) {}

    public record MarkRequest(
        @NotNull Long userId,
        @NotBlank @Size(max = 16) String label,
        /** Null takes the hand-set mark away, and the judge's mark stands again. */
        @DecimalMin("0") @DecimalMax("100000") Double marks,
        @Size(max = 1000) String remark
    ) {}
}
