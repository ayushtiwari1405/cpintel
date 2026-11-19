package com.cpintel.classrooms;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * What the classroom dashboards give: the class as a whole, and one student inside it.
 *
 * <p>Everything here is about the classroom's own work — its labs and its contests and
 * examinations. A student's private practice (roadmap, gauntlet, recommendations) is theirs
 * and is deliberately not part of either view.
 */
public final class ClassroomDashboardDto {

    private ClassroomDashboardDto() {}

    // ------------------------------------------------------------------ the class

    public record ClassDashboard(
        Long classroomId,
        String name,
        int students,
        List<LabColumn> labs,
        List<EventColumn> events,
        List<RosterRow> roster,
        Instant generatedAt
    ) {}

    /** One lab, summed over the students it was set for. */
    public record LabColumn(
        Long labId,
        String title,
        Instant opensAt,
        Instant dueAt,
        /** UPCOMING, OPEN or CLOSED. */
        String state,
        int problemCount,
        BigDecimal totalMarks,
        int studentCount,
        double averageSolved,
        double averageMarks,
        /** Students who solved every problem in time. */
        int finishedAll,
        /** Students who solved nothing, in time or late. */
        int solvedNone
    ) {}

    /** One contest or examination run in this classroom. */
    public record EventColumn(
        Long eventId,
        /** EXAM or CONTEST. */
        String kind,
        String name,
        String platform,
        Instant startsAt,
        Instant endsAt,
        String lifecycle,
        int assigned,
        /** Of those assigned, how many took part. */
        int sat,
        int problemCount,
        /** Null until a board has been built. */
        Double averageScore,
        Double maxScore,
        Double averageSolved,
        int focusLosses,
        /** When the board these figures come from was computed; null when there is none yet. */
        Instant boardAt
    ) {}

    public record RosterRow(
        Long userId,
        String username,
        String fullName,
        List<LabCell> labs,
        List<EventCell> events,
        int labSolved,
        int labProblems,
        BigDecimal labMarks,
        BigDecimal labMaxMarks,
        /** Events set for them that have started; one still to come is not counted. */
        int eventsAssigned,
        int eventsSat,
        int focusLosses
    ) {}

    /** Null fields mean the lab was not set for this student. */
    public record LabCell(Long labId, boolean assigned, int solved, int late, int problems,
                          BigDecimal marks) {}

    public record EventCell(Long eventId, boolean assigned, boolean sat, Integer rank,
                            Integer solved, Double score, int focusLosses) {}

    // --------------------------------------------------------------- one student

    public record StudentDashboard(
        Long classroomId,
        String classroomName,
        Long userId,
        String username,
        String fullName,
        String email,
        Instant joinedAt,
        List<String> teams,
        Handles handles,
        List<StudentLab> labs,
        List<StudentEvent> events
    ) {}

    public record Handles(String codeforces, boolean codeforcesVerified,
                          String leetcode, boolean leetcodeVerified,
                          String cses, boolean csesConnected) {}

    public record StudentLab(
        Long labId,
        String title,
        Instant opensAt,
        Instant dueAt,
        String state,
        List<LabProblem> problems,
        int solved,
        int late,
        BigDecimal marks,
        BigDecimal totalMarks
    ) {}

    /** status is SOLVED, LATE, or null when not solved. */
    public record LabProblem(Long problemId, String platform, String key, String title, String url,
                             String difficulty, BigDecimal marks, String status,
                             Instant solvedAt) {}

    public record StudentEvent(
        Long eventId,
        String kind,
        String name,
        String platform,
        Instant startsAt,
        Instant endsAt,
        String lifecycle,
        boolean sat,
        Integer rank,
        Integer rankedOf,
        Integer solved,
        Double score,
        Double maxScore,
        int submissions,
        int focusLosses,
        /** Per problem, from the stored board; empty when there is no board. */
        List<EventProblem> problems,
        Instant boardAt
    ) {}

    /** marks: what was earned, a marker's hand mark included. */
    public record EventProblem(String label, Double maxMarks, boolean solved, int wrongAttempts,
                               Double marks) {}
}
