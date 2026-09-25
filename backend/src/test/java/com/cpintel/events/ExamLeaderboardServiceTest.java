package com.cpintel.events;

import com.cpintel.compete.CompeteService;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.User;
import com.cpintel.entity.mongo.CodeSubmission;
import com.cpintel.repository.jpa.ContestProblemRepository;
import com.cpintel.repository.jpa.GroupContestRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.repository.mongo.CodeSubmissionRepository;
import com.cpintel.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * How an examination's leaderboard orders people: solved count, then time — where time is when
 * the accepted code was sent, not when its verdict arrived — plus a penalty per wrong attempt.
 */
class ExamLeaderboardServiceTest {

    private static final Instant START = Instant.parse("2026-09-25T09:00:00Z");

    private ExamLeaderboardService service;
    private GroupContest exam;
    private final Map<Long, User> users = new LinkedHashMap<>();
    private final List<CodeSubmission> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new ExamLeaderboardService(mock(EventService.class),
            mock(GroupContestRepository.class), mock(ContestProblemRepository.class),
            mock(CodeSubmissionRepository.class), mock(UserRepository.class),
            mock(CompeteService.class), mock(AuditService.class), new ObjectMapper());
        exam = GroupContest.builder().contestId(1L).kind("EXAM").platform("DOMJUDGE")
            .externalId("paper").name("Paper").startsAt(START)
            .endsAt(START.plusSeconds(3 * 3600)).wrongPenaltyMinutes(0).build();
        for (long id = 1; id <= 3; id++) {
            users.put(id, User.builder().userId(id).username("user" + id).build());
        }
    }

    private void sent(long userId, String label, int secondsIn, String verdict) {
        rows.add(CodeSubmission.builder().userId(userId).problemIndex(label).verdict(verdict)
            .submittedAt(START.plusSeconds(secondsIn)).build());
    }

    private EventsDto.LeaderboardStandings rank() {
        return service.rank(exam, List.of("A", "B"), users, rows, START.plusSeconds(9000));
    }

    private List<String> order() {
        return rank().rows().stream().map(EventsDto.LeaderboardRow::username).toList();
    }

    @Test
    @DisplayName("Sent first ranks first, whichever verdict came back first")
    void submissionTimeNotVerdictTime() {
        // user1 sent at 600s, user2 at 601s. The archive knows nothing about when verdicts
        // arrived, and must not: only the sending time decides.
        sent(2, "A", 601, "OK");
        sent(1, "A", 600, "OK");

        assertEquals(List.of("user1", "user2", "user3"), order());
    }

    @Test
    @DisplayName("More solved beats less time")
    void solvedCountFirst() {
        sent(1, "A", 100, "OK");
        sent(2, "A", 5000, "OK");
        sent(2, "B", 6000, "OK");

        EventsDto.LeaderboardStandings board = rank();
        assertEquals("user2", board.rows().get(0).username());
        assertEquals(2, board.rows().get(0).solved());
        assertEquals(11000, board.rows().get(0).totalSeconds());
    }

    @Test
    @DisplayName("Each wrong attempt before the solve adds the penalty; compile errors do not")
    void penalty() {
        exam.setWrongPenaltyMinutes(20);
        sent(1, "A", 100, "WRONG_ANSWER");
        sent(1, "A", 200, "COMPILATION_ERROR");
        sent(1, "A", 300, "OK");
        sent(1, "A", 400, "WRONG_ANSWER");   // after the solve: ignored
        sent(2, "A", 1000, "OK");

        EventsDto.LeaderboardStandings board = rank();
        // user1: 300 + 1 × 1200 = 1500 > user2's 1000.
        assertEquals(List.of("user2", "user1", "user3"), order());
        EventsDto.LeaderboardRow user1 = board.rows().get(1);
        assertEquals(1500, user1.totalSeconds());
        assertEquals(1, user1.cells().get(0).wrongAttempts());
    }

    @Test
    @DisplayName("Without a penalty, wrong attempts cost nothing")
    void noPenalty() {
        sent(1, "A", 100, "WRONG_ANSWER");
        sent(1, "A", 300, "OK");
        sent(2, "A", 400, "OK");

        assertEquals(List.of("user1", "user2", "user3"), order());
    }

    @Test
    @DisplayName("Equal solves and equal time share a rank; still-judging counts nothing yet")
    void tiesAndPending() {
        sent(1, "A", 300, "OK");
        sent(2, "A", 300, "OK");
        sent(3, "A", 200, "TESTING");

        EventsDto.LeaderboardStandings board = rank();
        assertEquals(1, board.rows().get(0).rank());
        assertEquals(1, board.rows().get(1).rank());
        assertEquals(3, board.rows().get(2).rank());
        assertTrue(board.rows().get(2).cells().get(0).pending());
        assertEquals(1, board.pendingSubmissions());
    }
}
