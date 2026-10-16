package com.cpintel.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class DesktopClientTest {

    private static MockHttpServletRequest with(String value) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (value != null) req.addHeader(DesktopClient.HEADER, value);
        return req;
    }

    @Test
    @DisplayName("no header, or an empty one, is not the desktop app")
    void absent() {
        assertNull(DesktopClient.version(with(null)));
        assertNull(DesktopClient.version(with("   ")));
        assertNull(DesktopClient.version(with("<>\"")));
        assertFalse(DesktopClient.isDesktop(null));
    }

    @Test
    @DisplayName("the version is kept, cleaned of anything that is not part of one, and capped")
    void cleaned() {
        assertEquals("1.4.0", DesktopClient.version(with(" 1.4.0 ")));
        assertEquals("1.4.0-beta+3", DesktopClient.version(with("1.4.0-beta+3")));
        assertEquals("1.0scriptalert1script",
            DesktopClient.version(with("1.0<script>alert(1)</script>")));
        assertEquals(40, DesktopClient.version(with("9".repeat(100))).length());
    }
}
