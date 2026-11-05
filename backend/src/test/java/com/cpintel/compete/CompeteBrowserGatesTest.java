package com.cpintel.compete;

import com.cpintel.events.EventWindow;
import com.cpintel.events.ExamSessionGuard;
import com.cpintel.events.ExamSessionRecorder;
import com.cpintel.events.LiveExamGuard;
import com.cpintel.exception.ApiException;
import com.cpintel.files.ContestFilePolicy;
import com.cpintel.files.PersonalFileService;
import com.cpintel.groups.LanguagePolicy;
import com.cpintel.groups.ProctoringGate;
import com.cpintel.practice.PracticeDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A Codeforces submission the desktop app or extension posts itself is held to the same gates
 * as one sent through the server: the examination, its window, its monitoring, its languages.
 */
class CompeteBrowserGatesTest {

    private static final Long USER = 5L;
    private static final String REF = "grp~730484";

    private final ExamSessionGuard examGuard = mock(ExamSessionGuard.class);
    private final ProctoringGate proctoring = mock(ProctoringGate.class);
    private final LanguagePolicy languages = mock(LanguagePolicy.class);
    private final EventWindow window = mock(EventWindow.class);
    private final ExamSessionRecorder sessions = mock(ExamSessionRecorder.class);
    private final CompeteService service = new CompeteService(List.of(), mock(PersonalFileService.class),
        examGuard, mock(ContestFilePolicy.class), proctoring, languages, sessions,
        mock(LiveExamGuard.class), window);

    @Test
    @DisplayName("runs the examination, window and monitoring checks, in that order")
    void gates() {
        when(languages.restrictionFor(USER, "CODEFORCES", REF)).thenReturn(Set.of());
        service.requireBrowserSubmit(USER, REF, "54", List.of());

        var order = inOrder(examGuard, window, proctoring);
        order.verify(examGuard).requireContestAccess(USER, "CODEFORCES", REF);
        order.verify(window).requireOpen(USER, "CODEFORCES", REF);
        order.verify(proctoring).requireMonitored(USER, "CODEFORCES", REF);
    }

    @Test
    @DisplayName("a silent machine is refused before anything is archived")
    void silentMachine() {
        doThrow(ApiException.forbidden("nothing is reporting")).when(proctoring)
            .requireMonitored(USER, "CODEFORCES", REF);
        assertThrows(ApiException.class, () -> service.requireBrowserSubmit(USER, REF, "54", List.of()));
    }

    @Test
    @DisplayName("an examination's languages are enforced against the page's own compiler list")
    void languages() {
        List<PracticeDto.LanguageOption> offered = List.of(new PracticeDto.LanguageOption("54", "GNU G++17"));
        when(languages.restrictionFor(USER, "CODEFORCES", REF)).thenReturn(Set.of("cpp"));
        when(languages.filter(offered, Set.of("cpp"))).thenReturn(offered);
        service.requireBrowserSubmit(USER, REF, "54", offered);
        verify(languages).requireAllowed(Set.of("cpp"), "54", offered);
    }

    @Test
    @DisplayName("statements read through the browser are held to the paper")
    void statement() {
        service.requireBrowserStatement(USER, REF);
        verify(examGuard).requirePaperContent(USER, "CODEFORCES", REF);
    }
}
