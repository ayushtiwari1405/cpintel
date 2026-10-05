package com.cpintel.events;

import com.cpintel.compete.CompeteDto;
import com.cpintel.compete.CompeteProvider;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * The compete arena's board: an event's own when the contest has one, and otherwise ranked the
 * same way from whoever submitted, inside the judge's window.
 */
class ContestLeaderboardServiceTest {

    private static final String CID = "5~demo";
    private static final long VIEWER = 1L;

    private final EventService events = mock(EventService.class);
    private final GroupContestRepository eventRepository = mock(GroupContestRepository.class);
    private final CodeSubmissionRepository submissions = mock(CodeSubmissionRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final CompeteService compete = mock(CompeteService.class);
    private final CompeteProvider provider = mock(CompeteProvider.class);
    private final EventWindow eventWindow = mock(EventWindow.class);

    private final List<CodeSubmission> rows = new ArrayList<>();
    private Instant start;
    private ContestLeaderboardService service;

    @BeforeEach
    void setUp() {
        ExamLeaderboardService boards = new ExamLeaderboardService(events, eventRepository,
            mock(ContestProblemRepository.class), submissions, userRepository, compete,
            mock(AuditService.class), new ObjectMapper().findAndRegisterModules(),
            mock(com.cpintel.repository.jpa.ExamMarkRepository.class));
        service = new ContestLeaderboardService(boards, mock(ExamSessionGuard.class),
            eventWindow, compete, userRepository);

        start = Instant.now().minusSeconds(3600);
        when(compete.provider("DOMJUDGE")).thenReturn(provider);
        when(provider.platform()).thenReturn("DOMJUDGE");
        when(eventWindow.governing(VIEWER, "DOMJUDGE", CID)).thenReturn(Optional.empty());
        when(submissions.findByPlatformAndContestId("DOMJUDGE", CID)).thenReturn(rows);
        when(userRepository.findAllById(any())).thenAnswer(call -> {
            List<User> found = new ArrayList<>();
            for (Object id : (Iterable<?>) call.getArgument(0)) {
                found.add(User.builder().userId((Long) id).username("user" + id).build());
            }
            return found;
        });
    }

    private void judgeContest(Instant startsAt) {
        when(provider.contestInfo(VIEWER, CID)).thenReturn(new CompeteDto.ContestInfo(
            CID, "Demo", "DOMJUDGE", "CODING", true, false, startsAt, 5 * 3600, 0, 0,
            true, null, false, CompeteDto.StatementFormat.PDF,
            List.of(new CompeteDto.ContestProblem("A", "First", null, null),
                new CompeteDto.ContestProblem("B", "Second", null, null)),
            "https://judge.example/team"));
    }

    private void sent(long userId, String label, Instant at, String verdict) {
        rows.add(CodeSubmission.builder().userId(userId).platform("DOMJUDGE").contestId(CID)
            .problemIndex(label).verdict(verdict).submittedAt(at).build());
    }

    @Test
    @DisplayName("With no event, whoever submitted is ranked by solves, then when they were sent")
    void rankedFromTheArchive() {
        judgeContest(start);
        sent(2, "A", start.plusSeconds(600), "OK");
        sent(3, "A", start.plusSeconds(300), "OK");
        sent(4, "B", start.plusSeconds(100), "WRONG_ANSWER");
        // A practice attempt from before the contest opened is not part of it.
        sent(5, "A", start.minusSeconds(60), "OK");

        EventsDto.Leaderboard board = service.forContest(VIEWER, "DOMJUDGE", CID);

        assertEquals("LIVE", board.state());
        assertNull(board.eventId());
        assertEquals(List.of("A", "B"), board.standings().problems());
        assertEquals(List.of("user3", "user2", "user4"), board.standings().rows().stream()
            .map(EventsDto.LeaderboardRow::username).toList());
        assertEquals(0, board.standings().penaltyMinutes());
        assertNotNull(board.nextRefreshAt());
    }

    @Test
    @DisplayName("The board is recomputed on a schedule, not for every reader")
    void oneComputationBetweenRefreshes() {
        judgeContest(start);
        sent(2, "A", start.plusSeconds(600), "OK");

        service.forContest(VIEWER, "DOMJUDGE", CID);
        service.forContest(VIEWER, "DOMJUDGE", CID);

        verify(submissions, times(1)).findByPlatformAndContestId("DOMJUDGE", CID);
    }

    @Test
    @DisplayName("A contest that has not started has no board yet")
    void notStarted() {
        judgeContest(Instant.now().plusSeconds(600));

        EventsDto.Leaderboard board = service.forContest(VIEWER, "DOMJUDGE", CID);

        assertEquals("NOT_STARTED", board.state());
        assertNull(board.standings());
    }

    @Test
    @DisplayName("A contest with an event shows that event's board, under its settings")
    void eventDecides() {
        GroupContest event = GroupContest.builder().contestId(9L).kind("CONTEST")
            .platform("DOMJUDGE").externalId(CID).name("Round 1").lifecycle("SCHEDULED")
            .startsAt(start).endsAt(start.plusSeconds(7200)).leaderboardEnabled(false).build();
        when(eventWindow.governing(VIEWER, "DOMJUDGE", CID)).thenReturn(Optional.of(event));

        EventsDto.Leaderboard board = service.forContest(VIEWER, "DOMJUDGE", CID);

        assertEquals(9L, board.eventId());
        assertEquals("DISABLED", board.state());
        assertNull(board.standings());
        verify(provider, never()).contestInfo(anyLong(), any());
    }

    @Test
    @DisplayName("An event's board keeps to the people it was assigned to")
    void eventRoster() {
        GroupContest event = GroupContest.builder().contestId(9L).kind("CONTEST")
            .platform("DOMJUDGE").externalId(CID).name("Round 1").lifecycle("SCHEDULED")
            .startsAt(start).endsAt(start.plusSeconds(7200)).build();
        when(eventWindow.governing(VIEWER, "DOMJUDGE", CID)).thenReturn(Optional.of(event));
        when(events.participantIds(9L)).thenReturn(Set.of(2L));
        sent(2, "A", start.plusSeconds(600), "OK");
        sent(3, "A", start.plusSeconds(300), "OK");

        EventsDto.Leaderboard board = service.forContest(VIEWER, "DOMJUDGE", CID);

        assertEquals(List.of("user2"), board.standings().rows().stream()
            .map(EventsDto.LeaderboardRow::username).toList());
    }

    @Test
    @DisplayName("A public contest ranks whoever submitted during it")
    void publicEventRoster() {
        GroupContest event = GroupContest.builder().contestId(9L).kind("CONTEST")
            .platform("DOMJUDGE").externalId(CID).name("Open round").lifecycle("SCHEDULED")
            .visibility("PUBLIC").startsAt(start).endsAt(start.plusSeconds(7200)).build();
        when(eventWindow.governing(VIEWER, "DOMJUDGE", CID)).thenReturn(Optional.of(event));
        when(events.participantIds(9L)).thenReturn(Set.of());
        sent(2, "A", start.plusSeconds(600), "OK");
        sent(3, "A", start.plusSeconds(300), "OK");

        EventsDto.Leaderboard board = service.forContest(VIEWER, "DOMJUDGE", CID);

        assertEquals(List.of("user3", "user2"), board.standings().rows().stream()
            .map(EventsDto.LeaderboardRow::username).toList());
    }

    @Test
    @DisplayName("The header's rank is this person's row on the board")
    void rankFromTheBoard() {
        judgeContest(start);
        sent(2, "A", start.plusSeconds(600), "OK");
        sent(VIEWER, "A", start.plusSeconds(900), "OK");

        CompeteDto.RankInfo rank = service.rank(VIEWER, "DOMJUDGE", CID);

        assertEquals(2, rank.rank());
        assertEquals(1, rank.solvedCount());
        assertEquals(15, rank.penalty());
        assertNull(rank.points());
        assertTrue(rank.participating());
    }

    @Test
    @DisplayName("Somebody who has not submitted is in the contest, without a rank")
    void rankBeforeSubmitting() {
        judgeContest(start);
        sent(2, "A", start.plusSeconds(600), "OK");

        CompeteDto.RankInfo rank = service.rank(VIEWER, "DOMJUDGE", CID);

        assertNull(rank.rank());
        assertTrue(rank.participating());
    }

    @Test
    @DisplayName("A board that is switched off gives no rank either")
    void noRankWithoutABoard() {
        GroupContest event = GroupContest.builder().contestId(9L).kind("EXAM")
            .platform("DOMJUDGE").externalId(CID).name("Paper").lifecycle("SCHEDULED")
            .startsAt(start).endsAt(start.plusSeconds(7200)).leaderboardEnabled(false).build();
        when(eventWindow.governing(VIEWER, "DOMJUDGE", CID)).thenReturn(Optional.of(event));

        CompeteDto.RankInfo rank = service.rank(VIEWER, "DOMJUDGE", CID);

        assertNull(rank.rank());
        assertFalse(rank.participating());
    }

    @Test
    @DisplayName("A Codeforces rank still comes from Codeforces")
    void codeforcesRankDelegated() {
        CompeteProvider codeforces = mock(CompeteProvider.class);
        when(codeforces.platform()).thenReturn("CODEFORCES");
        when(compete.provider("CODEFORCES")).thenReturn(codeforces);
        CompeteDto.RankInfo theirs =
            new CompeteDto.RankInfo(7, 1500.0, null, 3, false, true, Instant.now());
        when(compete.rank(VIEWER, "CODEFORCES", "2000")).thenReturn(theirs);

        assertSame(theirs, service.rank(VIEWER, "CODEFORCES", "2000"));
    }

    @Test
    @DisplayName("Codeforces has no board here")
    void codeforcesRefused() {
        CompeteProvider codeforces = mock(CompeteProvider.class);
        when(codeforces.platform()).thenReturn("CODEFORCES");
        when(compete.provider("CODEFORCES")).thenReturn(codeforces);

        assertThrows(RuntimeException.class,
            () -> service.forContest(VIEWER, "CODEFORCES", "2000"));
    }
}
