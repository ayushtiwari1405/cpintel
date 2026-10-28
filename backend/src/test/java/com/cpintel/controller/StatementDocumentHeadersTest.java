package com.cpintel.controller;

import com.cpintel.compete.CompeteDto;
import com.cpintel.compete.CompeteService;
import com.cpintel.events.ContestLeaderboardService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * A statement is whatever the judge's problem package holds, and HTML from a judge is not ours
 * to run on CPIntel's origin. Opened directly, it is sandboxed by the response itself.
 */
class StatementDocumentHeadersTest {

    private static ResponseEntity<byte[]> serve(String contentType) {
        CompeteService compete = mock(CompeteService.class);
        when(compete.statementDocument(1L, "domjudge", "c", "A"))
            .thenReturn(new CompeteDto.StatementDocument(new byte[]{1}, contentType));
        return new CompeteController(compete, mock(ContestLeaderboardService.class))
            .statementPdf(1L, "domjudge", "c", "A");
    }

    @Test
    @DisplayName("an HTML statement is served sandboxed")
    void htmlSandboxed() {
        assertEquals("sandbox", serve("text/html").getHeaders().getFirst("Content-Security-Policy"));
    }

    @Test
    @DisplayName("a PDF is not, since browsers will not show a PDF in a sandbox")
    void pdfUnchanged() {
        assertNull(serve("application/pdf").getHeaders().getFirst("Content-Security-Policy"));
    }

    @Test
    @DisplayName("a wildcard type from the judge is served as sandboxed bytes, not as a PDF")
    void wildcardSandboxed() {
        for (String wild : new String[]{"*/*", "application/*"}) {
            ResponseEntity<byte[]> r = serve(wild);
            assertEquals("sandbox", r.getHeaders().getFirst("Content-Security-Policy"), wild);
            assertEquals(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM,
                r.getHeaders().getContentType(), wild);
        }
    }
}
