package com.cpintel.events;

import com.cpintel.compete.CompeteDto;
import com.cpintel.compete.CompeteProvider;
import com.cpintel.compete.CompeteService;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.User;
import com.cpintel.entity.mongo.CodeSubmission;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.GroupContestRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** Marking an event done: when it is allowed, and what the build that follows does. */
class EventCompletionServiceTest {

    private static final long EVENT = 7L;
    private static final long ADMIN = 99L;
    private static final String CID = "5~paper";

    private final EventService events = mock(EventService.class);
    private final GroupContestRepository repository = mock(GroupContestRepository.class);
    private final ExamLeaderboardService boards = mock(ExamLeaderboardService.class);
    private final CompeteService compete = mock(CompeteService.class);
    private final CompeteProvider provider = mock(CompeteProvider.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final EventExportStore store = mock(EventExportStore.class);

    private EventCompletionService service;
    private GroupContest event;

    @BeforeEach
    void setUp() {
        // The build runs where it is started, so a test sees it finished.
        service = new EventCompletionService(events, repository, boards, compete, userRepository,
            store, mock(AuditService.class), Runnable::run);

        Instant start = Instant.now().minusSeconds(3 * 86400);
        event = GroupContest.builder().contestId(EVENT).classroomId(1L).kind("EXAM")
            .platform("DOMJUDGE").externalId(CID).name("Paper").lifecycle("SCHEDULED")
            .startsAt(start).endsAt(start.plusSeconds(7200)).build();
        when(events.require(EVENT)).thenReturn(event);
        when(compete.provider("DOMJUDGE")).thenReturn(provider);

        CodeSubmission row = CodeSubmission.builder().userId(1L).problemIndex("A").verdict("OK")
            .languageId("cpp").source("int main() {}").externalId(11L)
            .submittedAt(start.plusSeconds(600)).build();
        when(boards.attemptsOf(event)).thenReturn(List.of(row));
        when(boards.finalise(event)).thenReturn(new EventsDto.LeaderboardStandings(
            List.of("A"), java.util.Map.of("A", 1.0), false, List.of(), 0, 0, Instant.now()));
        when(userRepository.findAllById(any())).thenReturn(
            List.of(User.builder().userId(1L).username("asha").build()));
        when(provider.submissions(1L, CID)).thenReturn(List.of(mock(CompeteDto.ContestSubmission.class)));
        when(store.save(anyString(), any())).thenReturn("file-1");
        when(repository.storeExport(anyLong(), anyString(), anyLong(), any())).thenReturn(1);

        // What the repository's update would have done to the row the build then reads.
        when(repository.startExport(eq(EVENT), any(), any(), any())).thenAnswer(call -> {
            event.setCompletedAt(call.getArgument(1));
            event.setCompletedBy(call.getArgument(2));
            event.setExportStatus("BUILDING");
            event.setExportStartedAt(call.getArgument(3));
            return 1;
        });
    }

    @Test
    @DisplayName("Done re-reads verdicts, fixes the board, and stores the zip")
    void completes() {
        service.complete(ADMIN, EVENT, null);

        verify(provider).submissions(1L, CID);
        verify(boards).finalise(event);
        ArgumentCaptor<byte[]> zip = ArgumentCaptor.forClass(byte[].class);
        verify(store).save(eq("Paper-7.zip"), zip.capture());
        verify(repository).storeExport(EVENT, "file-1", (long) zip.getValue().length, null);
        assertNotNull(event.getCompletedAt());
        assertEquals(ADMIN, event.getCompletedBy());
    }

    @Test
    @DisplayName("An event that is still running cannot be marked done")
    void notWhileRunning() {
        event.setEndsAt(Instant.now().plusSeconds(600));

        assertThrows(ApiException.class, () -> service.complete(ADMIN, EVENT, null));
        verify(repository, never()).startExport(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("Nor can one already marked done")
    void notTwice() {
        event.setCompletedAt(Instant.now());

        assertThrows(ApiException.class, () -> service.complete(ADMIN, EVENT, null));
    }

    @Test
    @DisplayName("Verdicts the judge would not give are named in the export's note")
    void unreadableVerdicts() {
        when(provider.submissions(1L, CID)).thenThrow(new RuntimeException("401"));

        service.complete(ADMIN, EVENT, null);

        ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
        verify(repository).storeExport(eq(EVENT), eq("file-1"), anyLong(), note.capture());
        assertTrue(note.getValue().contains("1 of 1 people"));
    }

    @Test
    @DisplayName("A build that fails says so on the event rather than disappearing")
    void failedBuild() {
        when(boards.finalise(event)).thenThrow(new IllegalStateException("mongo is down"));

        service.complete(ADMIN, EVENT, null);

        verify(repository).failExport(eq(EVENT), contains("mongo is down"));
        verify(repository, never()).storeExport(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("A rebuild replaces the stored zip")
    void rebuild() {
        event.setCompletedAt(Instant.now().minusSeconds(3600));
        event.setCompletedBy(ADMIN);
        event.setExportStatus("READY");
        event.setExportFileId("old-file");

        service.rebuild(ADMIN, EVENT, null);

        verify(repository).storeExport(eq(EVENT), eq("file-1"), anyLong(), isNull());
        verify(store).delete("old-file");
    }

    @Test
    @DisplayName("Reopening clears the mark and discards the export")
    void reopen() {
        event.setCompletedAt(Instant.now());
        event.setExportStatus("READY");
        event.setExportFileId("old-file");

        service.reopen(ADMIN, EVENT, null);

        verify(repository).clearCompletion(EVENT);
        verify(store).delete("old-file");
    }

    @Test
    @DisplayName("Nothing is reopened or rebuilt while a build is running")
    void notWhileBuilding() {
        event.setCompletedAt(Instant.now());
        event.setExportStatus("BUILDING");
        event.setExportStartedAt(Instant.now());

        assertThrows(ApiException.class, () -> service.reopen(ADMIN, EVENT, null));
        assertThrows(ApiException.class, () -> service.rebuild(ADMIN, EVENT, null));
    }

    @Test
    @DisplayName("A build cut off by a restart counts as failed, so it can be started again")
    void staleBuild() {
        event.setCompletedAt(Instant.now().minusSeconds(7200));
        event.setExportStatus("BUILDING");
        event.setExportStartedAt(Instant.now().minusSeconds(7200));

        assertEquals("FAILED", event.exportState(Instant.now()));
        service.rebuild(ADMIN, EVENT, null);
        verify(repository).storeExport(eq(EVENT), eq("file-1"), anyLong(), isNull());
    }

    @Test
    @DisplayName("The zip is only handed over once it is ready")
    void downloadOnlyWhenReady() {
        event.setCompletedAt(Instant.now());
        event.setExportStatus("BUILDING");
        event.setExportStartedAt(Instant.now());
        assertThrows(ApiException.class, () -> service.export(ADMIN, EVENT, null));

        event.setExportStatus("READY");
        event.setExportFileId("file-1");
        event.setExportBytes(10L);
        when(store.open("file-1")).thenReturn(Optional.of(
            new org.springframework.core.io.ByteArrayResource(new byte[10])));
        assertEquals("Paper-7.zip", service.export(ADMIN, EVENT, null).fileName());
    }
}
