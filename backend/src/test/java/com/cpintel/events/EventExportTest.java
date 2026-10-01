package com.cpintel.events;

import com.cpintel.entity.GroupContest;
import com.cpintel.entity.User;
import com.cpintel.entity.mongo.CodeSubmission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** What an admin unpacks: submissions filed by username, and the leaderboard as a workbook. */
class EventExportTest {

    private static final Instant START = Instant.parse("2026-09-25T09:00:00Z");

    private GroupContest event;
    private final Map<Long, User> users = new LinkedHashMap<>();
    private final List<CodeSubmission> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        event = GroupContest.builder().contestId(7L).kind("EXAM").platform("DOMJUDGE")
            .externalId("5~paper").name("Mid-term: Paper 1").startsAt(START)
            .endsAt(START.plusSeconds(7200)).completedAt(START.plusSeconds(90000)).build();
        users.put(1L, User.builder().userId(1L).username("asha").fullName("Asha Rao").build());
        users.put(2L, User.builder().userId(2L).username("ben k").build());
    }

    private void sent(long userId, String label, int secondsIn, String verdict, String language,
                      String source) {
        rows.add(CodeSubmission.builder().userId(userId).problemIndex(label).verdict(verdict)
            .languageId(language).source(source).externalId((long) rows.size() + 100)
            .submittedAt(START.plusSeconds(secondsIn)).build());
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                files.put(entry.getName(), zip.readAllBytes());
            }
        }
        return files;
    }

    private EventsDto.LeaderboardStandings standings() {
        return new EventsDto.LeaderboardStandings(List.of("A", "B"), Map.of("A", 30.0, "B", 70.0),
            true,
            List.of(
                new EventsDto.LeaderboardRow(1, 1L, "asha", "Asha Rao", 1, 30.0, 900, List.of(
                    new EventsDto.LeaderboardCell("A", true, 1, 900L, false, true, 30.0),
                    new EventsDto.LeaderboardCell("B", false, 2, null, false, false, 0))),
                new EventsDto.LeaderboardRow(2, 2L, "ben k", null, 0, 0, 0, List.of())),
            0, 0, START.plusSeconds(8000));
    }

    @Test
    @DisplayName("Each submission is filed under its sender, named by problem, attempt and verdict")
    void filedByUsername() throws Exception {
        sent(1, "a", 600, "WRONG_ANSWER", "cpp", "int main() { return 1; }");
        sent(1, "A", 900, "OK", "cpp", "int main() {}");
        sent(2, "B", 300, null, "python3", "print(1)");

        Map<String, byte[]> files = unzip(EventExport.zip(event, standings(), rows, users, null,
            START.plusSeconds(90001)));

        assertEquals(List.of(
            "Mid-term_Paper_1/leaderboard.xlsx",
            "Mid-term_Paper_1/submissions/asha/A_01_WRONG_ANSWER.cpp",
            "Mid-term_Paper_1/submissions/asha/A_02_OK.cpp",
            "Mid-term_Paper_1/submissions/ben_k/B_01_PENDING.py"), List.copyOf(files.keySet()));
        assertEquals("int main() {}", new String(
            files.get("Mid-term_Paper_1/submissions/asha/A_02_OK.cpp"), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("The workbook lists the board and every submission with where its file is")
    void workbook() throws Exception {
        sent(1, "A", 900, "OK", "cpp", "int main() {}");

        Map<String, byte[]> files = unzip(EventExport.zip(event, standings(), rows, users,
            "2 submissions had no verdict yet.", START.plusSeconds(90001)));
        Map<String, byte[]> parts = unzip(files.get("Mid-term_Paper_1/leaderboard.xlsx"));

        String workbook = new String(parts.get("xl/workbook.xml"), StandardCharsets.UTF_8);
        assertTrue(workbook.contains("name=\"Leaderboard\""));
        assertTrue(workbook.contains("name=\"Submissions\""));
        assertTrue(workbook.contains("name=\"About\""));

        String board = new String(parts.get("xl/worksheets/sheet1.xml"), StandardCharsets.UTF_8);
        assertTrue(board.contains(">A (30)<"));
        assertTrue(board.contains(">asha<"));
        assertTrue(board.contains(">15:00 (+1)<"));   // solved A at 900s after one wrong try
        assertTrue(board.contains(">-2<"));           // two wrong tries at B, never solved
        assertTrue(board.contains("<v>30</v>"));      // marks are a number

        String listing = new String(parts.get("xl/worksheets/sheet2.xml"), StandardCharsets.UTF_8);
        assertTrue(listing.contains(">submissions/asha/A_01_OK.cpp<"));
        assertTrue(listing.contains(">2026-09-25 09:15:00 UTC<"));

        String about = new String(parts.get("xl/worksheets/sheet3.xml"), StandardCharsets.UTF_8);
        assertTrue(about.contains(">2 submissions had no verdict yet.<"));
    }

    @Test
    @DisplayName("Two usernames that come out the same do not share a folder")
    void collidingFolders() throws Exception {
        users.put(3L, User.builder().userId(3L).username("ben_k").build());
        sent(2, "A", 100, "OK", "java", "class A {}");
        sent(3, "A", 200, "OK", "java", "class B {}");

        Map<String, byte[]> files = unzip(EventExport.zip(event, standings(), rows, users, null,
            START.plusSeconds(90001)));

        assertTrue(files.containsKey("Mid-term_Paper_1/submissions/ben_k/A_01_OK.java"));
        assertTrue(files.containsKey("Mid-term_Paper_1/submissions/ben_k-3/A_01_OK.java"));
    }

    @Test
    @DisplayName("The leaderboard on its own is the board and its details")
    void leaderboardOnly() throws Exception {
        Map<String, byte[]> parts = unzip(
            EventExport.leaderboardWorkbook(event, standings(), START.plusSeconds(9000)));

        String workbook = new String(parts.get("xl/workbook.xml"), StandardCharsets.UTF_8);
        assertTrue(workbook.contains("name=\"Leaderboard\""));
        assertFalse(workbook.contains("name=\"Submissions\""));
    }
}
