package com.cpintel.classrooms;

import com.cpintel.classrooms.ClassroomDashboardDto.ClassDashboard;
import com.cpintel.classrooms.ClassroomDashboardDto.EventCell;
import com.cpintel.classrooms.ClassroomDashboardDto.EventColumn;
import com.cpintel.classrooms.ClassroomDashboardDto.LabCell;
import com.cpintel.classrooms.ClassroomDashboardDto.LabColumn;
import com.cpintel.classrooms.ClassroomDashboardDto.RosterRow;
import com.cpintel.classrooms.ClassroomDashboardService.EventResult;
import com.cpintel.classrooms.ClassroomDashboardService.Snapshot;
import com.cpintel.classrooms.ClassroomDashboardService.Standing;
import com.cpintel.common.Xlsx;
import com.cpintel.entity.ExamEvent;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.User;
import com.cpintel.events.EventsDto;
import com.cpintel.labs.LabsDto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The classroom reports as spreadsheets: the whole class, and one event.
 *
 * <p>Marks are written as numbers so a column sums and sorts; a cell that is not a mark
 * (absent, upcoming, not set) is a word, so it can never be mistaken for a zero. Times are in
 * IST, as every other export here writes them.
 */
final class ClassroomReports {

    private ClassroomReports() {}

    private static final DateTimeFormatter IST =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'IST'").withZone(ZoneId.of("Asia/Kolkata"));

    private static final String SOURCE_NOTE = "Event scores are from each event's last built "
        + "leaderboard, TA hand marks included. Late lab solves earn no marks.";

    // ------------------------------------------------------------------ the class

    static byte[] classWorkbook(Snapshot snap, Instant now) {
        ClassDashboard d = snap.dashboard();
        return new Xlsx()
            .sheet("Summary", summary(d, now))
            .sheet("Students", students(d))
            .sheet("Labs", labs(d.labs()))
            .sheet("Events", events(d.events()))
            .sheet("Lab problems", labProblems(snap.progress()))
            .sheet("Event questions", eventQuestions(snap))
            .toBytes();
    }

    private static List<List<Object>> summary(ClassDashboard d, Instant now) {
        int solved = 0, problems = 0, sat = 0, due = 0, look = 0, focus = 0;
        for (RosterRow r : d.roster()) {
            solved += r.labSolved();
            problems += r.labProblems();
            sat += r.eventsSat();
            due += r.eventsAssigned();
            focus += r.focusLosses();
            if (r.needsLook()) look++;
        }
        long exams = d.events().stream().filter(e -> "EXAM".equals(e.kind())).count();
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Classroom", d.name()));
        rows.add(List.of("Generated", IST.format(now)));
        rows.add(List.of("Students", d.students()));
        rows.add(List.of("Labs", d.labs().size()));
        rows.add(List.of("Examinations", exams));
        rows.add(List.of("Contests", d.events().size() - exams));
        rows.add(List.of("Lab problems solved in time", solved + " of " + problems));
        rows.add(List.of("Attendance at started events", sat + " of " + due));
        rows.add(List.of("Students needing a look", look));
        rows.add(List.of("Focus losses in exams", focus));
        rows.add(List.of("Note", SOURCE_NOTE));
        return rows;
    }

    private static List<List<Object>> students(ClassDashboard d) {
        Map<Long, EventColumn> eventById = new HashMap<>();
        for (EventColumn e : d.events()) eventById.put(e.eventId(), e);

        List<Object> header = new ArrayList<>(List.of("Username", "Full name"));
        for (LabColumn l : d.labs()) header.add(l.title() + " (solved of " + l.problemCount() + ")");
        for (EventColumn e : d.events()) header.add(e.name() + " (" + kind(e.kind()) + " score)");
        header.addAll(List.of("Lab marks", "Lab marks out of", "Events attended",
            "Events started", "Focus losses", "Needs a look"));

        List<List<Object>> rows = new ArrayList<>();
        rows.add(header);
        for (RosterRow r : d.roster()) {
            List<Object> row = new ArrayList<>();
            row.add(r.username());
            row.add(text(r.fullName()));
            for (LabCell c : r.labs()) row.add(c.assigned() ? c.solved() : "not set");
            for (EventCell c : r.events()) row.add(eventCell(c, eventById.get(c.eventId())));
            row.add(r.labMarks());
            row.add(r.labMaxMarks());
            row.add(r.eventsSat());
            row.add(r.eventsAssigned());
            row.add(r.focusLosses());
            row.add(r.needsLook() ? "yes" : "no");
            rows.add(row);
        }
        return rows;
    }

    private static Object eventCell(EventCell c, EventColumn e) {
        if (!c.assigned()) return "not assigned";
        if (e != null && "SCHEDULED".equals(e.lifecycle())) return "upcoming";
        if (!c.sat()) return "absent";
        if (c.score() != null) return c.score();
        if (c.solved() != null) return c.solved();
        return "attended";
    }

    private static List<List<Object>> labs(List<LabColumn> labs) {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Lab", "State", "Opens", "Due", "Problems", "Total marks", "Students",
            "Average solved", "Average marks", "Finished all", "Solved nothing"));
        for (LabColumn l : labs) {
            rows.add(List.of(l.title(), l.state(), IST.format(l.opensAt()), IST.format(l.dueAt()),
                l.problemCount(), l.totalMarks(), l.studentCount(), round(l.averageSolved()),
                round(l.averageMarks()), l.finishedAll(), l.solvedNone()));
        }
        return rows;
    }

    private static List<List<Object>> events(List<EventColumn> events) {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Event", "Kind", "Platform", "Starts", "Ends", "State", "Assigned",
            "Attended", "Problems", "Average score", "Out of", "Average solved", "Focus losses",
            "Leaderboard built"));
        for (EventColumn e : events) {
            rows.add(List.of(e.name(), kind(e.kind()), text(e.platform()), time(e.startsAt()),
                time(e.endsAt()), e.lifecycle(), e.assigned(), e.sat(), e.problemCount(),
                number(e.averageScore()), number(e.maxScore()), number(e.averageSolved()),
                e.focusLosses(), e.boardAt() == null ? "not yet" : IST.format(e.boardAt())));
        }
        return rows;
    }

    /** One row per student per lab problem. */
    private static List<List<Object>> labProblems(List<LabsDto.Progress> progress) {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Username", "Full name", "Lab", "No.", "Problem", "Platform", "Status",
            "Solved at", "Marks earned", "Marks available"));
        for (LabsDto.Progress p : progress) {
            List<LabsDto.ProblemView> problems = p.lab().problems();
            for (LabsDto.StudentRow s : p.students()) {
                Map<Long, LabsDto.Cell> cells = new HashMap<>();
                for (LabsDto.Cell c : s.cells()) cells.put(c.problemId(), c);
                for (int i = 0; i < problems.size(); i++) {
                    LabsDto.ProblemView pr = problems.get(i);
                    LabsDto.Cell c = cells.get(pr.problemId());
                    String status = c == null || c.status() == null ? "not solved"
                        : "LATE".equals(c.status()) ? "late" : "solved";
                    rows.add(List.of(s.username(), text(s.fullName()), p.lab().title(), i + 1,
                        pr.title(), pr.platform(), status,
                        c == null || c.solvedAt() == null ? "" : IST.format(c.solvedAt()),
                        "solved".equals(status) ? pr.marks() : BigDecimal.ZERO, pr.marks()));
                }
            }
        }
        return rows;
    }

    /** One row per student per question, for every event with a board. */
    private static List<List<Object>> eventQuestions(Snapshot snap) {
        Map<Long, User> byId = new HashMap<>();
        for (User u : snap.students()) byId.put(u.getUserId(), u);

        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Username", "Full name", "Event", "Kind", "Question", "Marks",
            "Out of", "Accepted", "Wrong attempts"));
        for (EventResult r : snap.results()) {
            if (r.labels().isEmpty()) continue;
            for (User u : snap.students()) {
                Standing s = r.board().get(u.getUserId());
                if (s == null) continue;
                Map<String, EventsDto.LeaderboardCell> cells = cellsOf(s);
                for (String label : r.labels()) {
                    EventsDto.LeaderboardCell c = cells.get(label);
                    rows.add(List.of(u.getUsername(), text(u.getFullName()), r.event().getName(),
                        kind(r.event().isExam() ? "EXAM" : "CONTEST"), label,
                        c == null ? 0.0 : c.marks(), max(r, label),
                        c != null && c.solved() ? "yes" : "no", c == null ? 0 : c.wrongAttempts()));
                }
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------ one event

    static byte[] eventWorkbook(EventResult r, List<User> people, Instant now) {
        GroupContest event = r.event();
        boolean started = event.hasStarted(now);

        List<Object> header = new ArrayList<>(List.of("Username", "Full name", "Assigned",
            "Attended", "Rank", "Score", "Out of", "Solved"));
        for (String label : r.labels()) header.add(label + " (out of " + plain(max(r, label)) + ")");
        header.addAll(List.of("Wrong attempts", "Submissions", "Focus losses"));

        List<List<Object>> results = new ArrayList<>();
        results.add(header);
        int attended = 0;
        for (User u : people) {
            Long id = u.getUserId();
            Standing s = r.board().get(id);
            boolean sat = r.sat(id);
            if (sat) attended++;
            List<Object> row = new ArrayList<>();
            row.add(u.getUsername());
            row.add(text(u.getFullName()));
            row.add(r.assigned().contains(id) ? "yes" : "no");
            row.add(!started ? "upcoming" : sat ? "yes" : "absent");
            row.add(s == null || s.rank() == null ? "" : s.rank());
            row.add(s == null || s.score() == null ? "" : s.score());
            row.add(r.maxScore() == null ? "" : r.maxScore());
            row.add(s == null || s.solved() == null ? "" : s.solved());
            Map<String, EventsDto.LeaderboardCell> cells = s == null ? Map.of() : cellsOf(s);
            int wrong = 0;
            for (String label : r.labels()) {
                EventsDto.LeaderboardCell c = cells.get(label);
                row.add(c == null ? "" : c.marks());
                if (c != null) wrong += c.wrongAttempts();
            }
            row.add(wrong);
            row.add(r.count(id, ExamEvent.Type.PROBLEM_SUBMITTED));
            row.add(r.count(id, ExamEvent.Type.FOCUS_LOST));
            results.add(row);
        }

        List<List<Object>> about = List.of(
            List.of("Event", event.getName()),
            List.of("Kind", kind(event.isExam() ? "EXAM" : "CONTEST")),
            List.of("Platform", text(event.getPlatform())),
            List.of("Starts", time(event.getStartsAt())),
            List.of("Ends", time(event.getEndsAt())),
            List.of("State", event.effectiveLifecycle(now).name()),
            List.of("Assigned", r.assigned().size()),
            List.of("Attended", attended),
            List.of("Leaderboard built", r.boardAt() == null ? "not yet" : IST.format(r.boardAt())),
            List.of("Generated", IST.format(now)),
            List.of("Note", SOURCE_NOTE));

        return new Xlsx().sheet("Results", results).sheet("Event", about).toBytes();
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, EventsDto.LeaderboardCell> cellsOf(Standing s) {
        Map<String, EventsDto.LeaderboardCell> out = new HashMap<>();
        for (EventsDto.LeaderboardCell c : s.cells()) out.put(c.label(), c);
        return out;
    }

    private static double max(EventResult r, String label) {
        return r.marked() ? r.marks().getOrDefault(label, 0.0) : 1.0;
    }

    private static String kind(String kind) {
        return "EXAM".equals(kind) ? "exam" : "contest";
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static Object time(Instant at) {
        return at == null ? "" : IST.format(at);
    }

    private static Object number(Double value) {
        return value == null ? "" : round(value);
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static String plain(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** A name safe for a file: letters, digits, dot, dash and underscore. */
    static String safe(String value, String fallback) {
        String clean = value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        clean = clean.replaceAll("^[._]+|[._]+$", "");
        if (clean.isEmpty()) return fallback;
        return clean.length() > 80 ? clean.substring(0, 80) : clean;
    }
}
