package com.cpintel.events;

import com.cpintel.common.Languages;
import com.cpintel.common.Xlsx;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.User;
import com.cpintel.entity.mongo.CodeSubmission;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * An event's record, as files: the leaderboard as a spreadsheet, and the zip an admin downloads
 * once the event is done.
 *
 * <p>The zip holds one folder, named after the event:
 * <pre>
 *   leaderboard.xlsx                       the board, every submission listed, and the details
 *   submissions/&lt;username&gt;/A_01_WRONG_ANSWER.cpp
 *   submissions/&lt;username&gt;/A_02_OK.cpp     each attempt, as it was sent
 * </pre>
 * A source file is named for its problem, which attempt on that problem it was, and what the
 * judge said, so a folder reads in order without opening anything.
 *
 * <p>Nothing here reads a database: it is handed the board and the submissions and lays them
 * out, which is what lets it be tested with neither.
 */
final class EventExport {

    private EventExport() {}

    /**
     * Every time in the workbook is Indian Standard Time, and says so. Fixed for now: the
     * people reading these are in one place, and a file has no viewer whose zone it could ask.
     */
    private static final DateTimeFormatter IST =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'IST'").withZone(ZoneId.of("Asia/Kolkata"));

    /** What a source file is saved as, by which of our languages it is in. */
    private static final Map<String, String> EXTENSIONS = Map.ofEntries(
        Map.entry("cpp", "cpp"), Map.entry("python3", "py"), Map.entry("java", "java"),
        Map.entry("c", "c"), Map.entry("csharp", "cs"), Map.entry("kotlin", "kt"),
        Map.entry("go", "go"), Map.entry("rust", "rs"), Map.entry("javascript", "js"),
        Map.entry("ruby", "rb"), Map.entry("scala", "scala"), Map.entry("php", "php"),
        Map.entry("pascal", "pas"), Map.entry("haskell", "hs"), Map.entry("ocaml", "ml"),
        Map.entry("sql", "sql"));

    // ------------------------------------------------------------ spreadsheet

    /** The leaderboard on its own: the board, and the details it was ranked under. */
    static byte[] leaderboardWorkbook(GroupContest event, EventsDto.LeaderboardStandings standings,
                                      Instant now) {
        return new Xlsx()
            .sheet("Leaderboard", leaderboardRows(standings))
            .sheet("About", aboutRows(event, standings, null, null, now))
            .toBytes();
    }

    private static List<List<Object>> leaderboardRows(EventsDto.LeaderboardStandings standings) {
        boolean marked = standings.marked();
        Map<String, Double> worth = standings.marks() == null ? Map.of() : standings.marks();

        List<Object> header = new ArrayList<>(List.of("Rank", "Username", "Name"));
        if (marked) header.add("Marks");
        header.add("Solved");
        header.add("Time");
        for (String label : standings.problems()) {
            header.add(marked ? label + " (" + number(worth.getOrDefault(label, 0.0)) + ")" : label);
        }
        // Marks per problem, once there is more to them than solved or not: set by the admin,
        // or by a marker during evaluation. A hand-set mark is starred.
        boolean evaluated = standings.rows().stream()
            .anyMatch(r -> r.cells().stream().anyMatch(EventsDto.LeaderboardCell::evaluated));
        boolean perProblem = marked || evaluated;
        if (perProblem) {
            for (String label : standings.problems()) header.add(label + " marks");
        }

        List<List<Object>> rows = new ArrayList<>();
        rows.add(header);
        for (EventsDto.LeaderboardRow row : standings.rows()) {
            List<Object> line = new ArrayList<>();
            line.add(row.rank());
            line.add(row.username());
            line.add(row.fullName());
            if (marked) line.add(row.score());
            line.add(row.solved());
            line.add(row.solved() > 0 ? clock(row.totalSeconds()) : null);

            Map<String, EventsDto.LeaderboardCell> cells = new HashMap<>();
            for (EventsDto.LeaderboardCell cell : row.cells()) cells.put(cell.label(), cell);
            for (String label : standings.problems()) line.add(cellText(cells.get(label)));
            if (perProblem) {
                for (String label : standings.problems()) {
                    EventsDto.LeaderboardCell cell = cells.get(label);
                    if (cell == null) line.add(null);
                    else if (cell.evaluated()) line.add(number(cell.marks()) + "*");
                    else line.add(cell.marks());
                }
            }
            rows.add(line);
        }
        return rows;
    }

    /** As the board on screen shows it: when it was solved and after how many wrong tries. */
    private static String cellText(EventsDto.LeaderboardCell cell) {
        if (cell == null) return null;
        if (cell.solved()) {
            String at = clock(cell.solvedAtSeconds() == null ? 0 : cell.solvedAtSeconds());
            return cell.wrongAttempts() > 0 ? at + " (+" + cell.wrongAttempts() + ")" : at;
        }
        if (cell.pending()) return "judging";
        return cell.wrongAttempts() > 0 ? "-" + cell.wrongAttempts() : null;
    }

    private static List<List<Object>> aboutRows(GroupContest event,
                                                EventsDto.LeaderboardStandings standings,
                                                Integer submissionCount, String note,
                                                Instant now) {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Detail", "Value"));
        rows.add(pair("Name", event.getName()));
        rows.add(pair("Kind", event.isExam() ? "Examination" : "Contest"));
        rows.add(pair("Judge", event.getPlatform()));
        rows.add(pair("Judge contest",
            com.cpintel.integration.domjudge.JudgeContestRef.judgeIdOf(event.getExternalId())));
        rows.add(pair("Started", event.getStartsAt() == null ? null : IST.format(event.getStartsAt())));
        rows.add(pair("Ended", event.getEndsAt() == null ? null : IST.format(event.getEndsAt())));
        if (standings != null) {
            rows.add(pair("Ranked by", (standings.marked() ? "marks" : "problems solved")
                + ", then less total time; a solve is timed from when it was sent"));
            if (standings.rows().stream().anyMatch(
                    r -> r.cells().stream().anyMatch(EventsDto.LeaderboardCell::evaluated))) {
                rows.add(pair("Marks marked *", "set by hand during evaluation, in place of "
                    + "the judge's all-or-nothing mark"));
            }
            rows.add(pair("Penalty per wrong attempt", standings.penaltyMinutes() > 0
                ? standings.penaltyMinutes() + " minutes" : "none"));
            rows.add(pair("People on the board", standings.rows().size()));
            rows.add(pair("Board computed", IST.format(standings.generatedAt())));
            if (standings.pendingSubmissions() > 0) {
                rows.add(pair("Still being judged", standings.pendingSubmissions()));
            }
        }
        if (submissionCount != null) rows.add(pair("Submissions", submissionCount));
        if (event.getCompletedAt() != null) {
            rows.add(pair("Marked done", IST.format(event.getCompletedAt())));
        }
        if (note != null && !note.isBlank()) rows.add(pair("Note", note));
        rows.add(pair("File written", IST.format(now)));
        return rows;
    }

    private static List<Object> pair(String key, Object value) {
        List<Object> row = new ArrayList<>(2);
        row.add(key);
        row.add(value);
        return row;
    }

    // -------------------------------------------------------------------- zip

    /**
     * The whole record: every submission, filed under whoever sent it, and the workbook.
     *
     * @param rows  the event's submissions — exactly the ones the board was ranked from
     * @param users whoever those belong to, by id; somebody missing is filed under their id
     * @param note  anything the build could not do, repeated on the About sheet
     */
    static byte[] zip(GroupContest event, EventsDto.LeaderboardStandings standings,
                      List<CodeSubmission> rows, Map<Long, User> users, String note,
                      Instant now) {
        List<CodeSubmission> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator
            .comparing((CodeSubmission row) -> usernameOf(row, users), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(EventExport::labelOf)
            .thenComparing(CodeSubmission::getSubmittedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));

        Map<Long, String> folders = folders(ordered, users);
        Map<CodeSubmission, String> paths = new IdentityHashMap<>();
        Map<CodeSubmission, Integer> attempt = new IdentityHashMap<>();
        Map<String, Integer> counted = new HashMap<>();
        for (CodeSubmission row : ordered) {
            int n = counted.merge(row.getUserId() + "|" + labelOf(row), 1, Integer::sum);
            attempt.put(row, n);
            paths.put(row, "submissions/" + folders.get(row.getUserId()) + "/"
                + String.format(Locale.ROOT, "%s_%02d_%s.%s", safe(labelOf(row), "problem"), n,
                    safe(row.getVerdict() == null ? "PENDING" : row.getVerdict(), "PENDING"),
                    extension(row)));
        }

        List<List<Object>> listing = new ArrayList<>();
        // Only a rejudged event has anything to say in the last column.
        boolean rejudged = ordered.stream().anyMatch(row -> row.getRejudgedAt() != null);
        List<Object> listingHeader = new ArrayList<>(List.of("Username", "Name", "Problem",
            "Attempt", "Verdict", "Language", "Submitted", "From start", "Judge submission",
            "File"));
        if (rejudged) listingHeader.add("Verdict before rejudge");
        listing.add(listingHeader);
        for (CodeSubmission row : ordered) {
            User user = users.get(row.getUserId());
            List<Object> line = new ArrayList<>();
            line.add(usernameOf(row, users));
            line.add(user == null ? null : user.getFullName());
            line.add(labelOf(row));
            line.add(attempt.get(row));
            line.add(row.getVerdict() == null ? "PENDING" : row.getVerdict());
            line.add(row.getLanguageLabel() != null ? row.getLanguageLabel() : row.getLanguageId());
            line.add(row.getSubmittedAt() == null ? null : IST.format(row.getSubmittedAt()));
            line.add(row.getSubmittedAt() == null || event.getStartsAt() == null ? null
                : clock(Math.max(0,
                    Duration.between(event.getStartsAt(), row.getSubmittedAt()).toSeconds())));
            line.add(row.getExternalId());
            line.add(paths.get(row));
            if (rejudged) line.add(row.getRejudgedAt() == null ? null : row.getVerdictBeforeRejudge());
            listing.add(line);
        }

        Xlsx workbook = new Xlsx();
        if (standings != null) workbook.sheet("Leaderboard", leaderboardRows(standings));
        workbook.sheet("Submissions", listing)
            .sheet("About", aboutRows(event, standings, ordered.size(), note, now));

        String root = safe(event.getName(), "event") + "/";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            put(zip, root + "leaderboard.xlsx", workbook.toBytes(), now);
            for (CodeSubmission row : ordered) {
                String source = row.getSource() == null ? "" : row.getSource();
                put(zip, root + paths.get(row), source.getBytes(StandardCharsets.UTF_8),
                    row.getSubmittedAt() == null ? now : row.getSubmittedAt());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** What the download is called. */
    static String fileName(GroupContest event) {
        return safe(event.getName(), "event") + "-" + event.getContestId();
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes, Instant at)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setLastModifiedTime(FileTime.from(at));
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    /**
     * One folder per person, named by username.
     *
     * Two usernames can come out the same once the characters a path cannot hold are replaced;
     * the later one takes its id as a suffix rather than sharing a folder with the first.
     */
    private static Map<Long, String> folders(List<CodeSubmission> ordered, Map<Long, User> users) {
        Map<Long, String> folders = new HashMap<>();
        Set<String> taken = new HashSet<>();
        for (CodeSubmission row : ordered) {
            if (folders.containsKey(row.getUserId())) continue;
            String name = safe(usernameOf(row, users), "user-" + row.getUserId());
            if (!taken.add(name.toLowerCase(Locale.ROOT))) {
                name = name + "-" + row.getUserId();
                taken.add(name.toLowerCase(Locale.ROOT));
            }
            folders.put(row.getUserId(), name);
        }
        return folders;
    }

    private static String usernameOf(CodeSubmission row, Map<Long, User> users) {
        User user = users.get(row.getUserId());
        return user == null || user.getUsername() == null
            ? "user-" + row.getUserId() : user.getUsername();
    }

    private static String labelOf(CodeSubmission row) {
        return row.getProblemIndex() == null ? "" : row.getProblemIndex().toUpperCase(Locale.ROOT);
    }

    private static String extension(CodeSubmission row) {
        String language = Languages.classify(row.getLanguageId(), row.getLanguageLabel());
        return language == null ? "txt" : EXTENSIONS.getOrDefault(language, "txt");
    }

    /** A name that is safe as one path segment on any system that will unpack the zip. */
    private static String safe(String value, String fallback) {
        String clean = value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        clean = clean.replaceAll("^[._]+|[._]+$", "");
        if (clean.isEmpty()) return fallback;
        return clean.length() > 80 ? clean.substring(0, 80) : clean;
    }

    /** h:mm:ss, or m:ss under an hour — as the board on screen writes a time. */
    private static String clock(long seconds) {
        long h = seconds / 3600, m = seconds % 3600 / 60, s = seconds % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
            : String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    /** 30 rather than 30.0. */
    private static String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
