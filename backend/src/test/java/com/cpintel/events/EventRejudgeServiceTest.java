package com.cpintel.events;

import com.cpintel.archive.SubmissionArchive;
import com.cpintel.compete.CompeteProvider;
import com.cpintel.compete.CompeteService;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.mongo.CodeSubmission;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.GroupContestRepository;
import com.cpintel.repository.mongo.CodeSubmissionRepository;
import com.cpintel.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Rejudging a problem: what is sent, in what order, and what the event says afterwards. */
class EventRejudgeServiceTest {

    private static final long EVENT = 7L;
    private static final long ADMIN = 99L;
    private static final String CID = "5~paper";

    private final EventService events = mock(EventService.class);
    private final GroupContestRepository repository = mock(GroupContestRepository.class);
    private final ExamLeaderboardService boards = mock(ExamLeaderboardService.class);
    private final CompeteService compete = mock(CompeteService.class);
    private final CompeteProvider provider = mock(CompeteProvider.class);
    private final SubmissionArchive archive = mock(SubmissionArchive.class);
    private final CodeSubmissionRepository submissions = mock(CodeSubmissionRepository.class);

    private final List<CodeSubmission> rows = new ArrayList<>();
    private EventRejudgeService service;
    private GroupContest event;
    private Instant start;

    @BeforeEach
    void setUp() {
        // Runs where it is started and never sleeps, so a test sees it finished.
        service = new EventRejudgeService(events, repository, boards, compete, archive,
            submissions, mock(AuditService.class), Runnable::run, Duration.ZERO, Duration.ZERO);

        start = Instant.now().minusSeconds(86400);
        event = GroupContest.builder().contestId(EVENT).classroomId(1L).kind("EXAM")
            .platform("DOMJUDGE").externalId(CID).name("Paper").lifecycle("SCHEDULED")
            .startsAt(start).endsAt(start.plusSeconds(7200)).build();
        when(events.require(EVENT)).thenReturn(event);
        when(compete.provider("DOMJUDGE")).thenReturn(provider);
        when(boards.attemptsOf(event)).thenReturn(rows);
        when(submissions.findAllById(any())).thenAnswer(call -> {
            List<CodeSubmission> found = new ArrayList<>();
            for (Object id : (Iterable<?>) call.getArgument(0)) {
                rows.stream().filter(row -> row.getId().equals(id)).forEach(found::add);
            }
            return found;
        });
        // The archive repoints the row; the judge then answers every resent submission OK.
        when(archive.markResent(anyString(), anyString())).thenAnswer(call -> {
            CodeSubmission row = rows.stream()
                .filter(r -> r.getId().equals(call.getArgument(0))).findFirst().orElseThrow();
            row.setVerdictBeforeRejudge(row.getVerdict());
            row.setVerdict("TESTING");
            return true;
        });
        when(provider.submissions(anyLong(), eq(CID))).thenAnswer(call -> {
            rows.stream().filter(r -> r.getUserId().equals(call.getArgument(0)))
                .filter(r -> "TESTING".equals(r.getVerdict())).forEach(r -> r.setVerdict("OK"));
            return List.of();
        });
    }

    private void sent(String id, long userId, String label, int secondsIn, String verdict) {
        rows.add(CodeSubmission.builder().id(id).userId(userId).problemIndex(label)
            .verdict(verdict).languageId("cpp").source("code of " + id)
            .submittedAt(start.plusSeconds(secondsIn)).build());
    }

    @Test
    @DisplayName("Every attempt at the problem is sent again, oldest first, as its author")
    void resendsInOrder() {
        sent("late", 2, "A", 900, "WRONG_ANSWER");
        sent("early", 1, "a", 100, "WRONG_ANSWER");
        sent("other", 1, "B", 50, "OK");
        when(provider.resubmit(anyLong(), eq(CID), eq("A"), eq("cpp"), anyString()))
            .thenReturn("501", "502");

        service.start(ADMIN, EVENT, " a ", null);

        InOrder order = inOrder(provider, archive);
        order.verify(provider).resubmit(1L, CID, "A", "cpp", "code of early");
        order.verify(archive).markResent("early", "501");
        order.verify(provider).resubmit(2L, CID, "A", "cpp", "code of late");
        order.verify(archive).markResent("late", "502");
        verify(provider, never()).resubmit(anyLong(), any(), eq("B"), any(), any());
    }

    @Test
    @DisplayName("The board is re-ranked once the verdicts are back, and the event says how it went")
    void reranksAndReports() {
        sent("s1", 1, "A", 100, "WRONG_ANSWER");
        sent("s2", 2, "A", 200, "OK");
        when(provider.resubmit(anyLong(), any(), any(), any(), any())).thenReturn("501", "502");

        service.start(ADMIN, EVENT, "A", null);

        verify(repository).startRejudge(eq(EVENT), eq("A"), any());
        verify(boards).finalise(event);
        ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
        verify(repository).finishRejudge(eq(EVENT), eq("DONE"), note.capture());
        assertTrue(note.getValue().contains("2 of 2 submissions sent"));
        assertTrue(note.getValue().contains("2 verdicts are back, 1 changed"));
    }

    @Test
    @DisplayName("A submission the judge refuses keeps its verdict, and the note says so")
    void refusedSubmissions() {
        sent("s1", 1, "A", 100, "WRONG_ANSWER");
        sent("s2", 2, "A", 200, "WRONG_ANSWER");
        when(provider.resubmit(eq(1L), any(), any(), any(), any())).thenReturn("501");
        when(provider.resubmit(eq(2L), any(), any(), any(), any()))
            .thenThrow(ApiException.forbidden("No DOMjudge login is attached"));

        service.start(ADMIN, EVENT, "A", null);

        assertEquals("WRONG_ANSWER", rows.get(1).getVerdict());
        ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
        verify(repository).finishRejudge(eq(EVENT), eq("DONE"), note.capture());
        assertTrue(note.getValue().contains("1 of 2 submissions sent"));
        assertTrue(note.getValue().contains("1 could not be sent"));
        assertTrue(note.getValue().contains("No DOMjudge login is attached"));
    }

    @Test
    @DisplayName("If the judge takes nothing, the rejudge failed")
    void nothingSent() {
        sent("s1", 1, "A", 100, "WRONG_ANSWER");
        when(provider.resubmit(anyLong(), any(), any(), any(), any()))
            .thenThrow(ApiException.badRequest("The contest is closed"));

        service.start(ADMIN, EVENT, "A", null);

        verify(repository).finishRejudge(eq(EVENT), eq("FAILED"), anyString());
    }

    @Test
    @DisplayName("A verdict the judge has not given in time is reported, not waited on for ever")
    void slowJudge() {
        sent("s1", 1, "A", 100, "WRONG_ANSWER");
        when(provider.resubmit(anyLong(), any(), any(), any(), any())).thenReturn("501");
        when(provider.submissions(anyLong(), eq(CID))).thenReturn(List.of());

        service.start(ADMIN, EVENT, "A", null);

        ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
        verify(repository).finishRejudge(eq(EVENT), eq("DONE"), note.capture());
        assertTrue(note.getValue().contains("1 still being judged"));
    }

    @Test
    @DisplayName("Refused: a done event, one mid-rejudge, a problem nobody attempted, Codeforces")
    void refusals() {
        sent("s1", 1, "A", 100, "OK");

        assertThrows(ApiException.class, () -> service.start(ADMIN, EVENT, "Z", null));

        event.setCompletedAt(Instant.now());
        assertThrows(ApiException.class, () -> service.start(ADMIN, EVENT, "A", null));
        event.setCompletedAt(null);

        event.setRejudgeStatus("RUNNING");
        event.setRejudgeStartedAt(Instant.now());
        assertThrows(ApiException.class, () -> service.start(ADMIN, EVENT, "A", null));
        event.setRejudgeStatus(null);

        event.setPlatform("CODEFORCES");
        assertThrows(ApiException.class, () -> service.start(ADMIN, EVENT, "A", null));

        verify(repository, never()).startRejudge(anyLong(), any(), any());
    }
}
