package com.cpintel.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Whether a request came from the CPIntel desktop app.
 *
 * <p>The desktop app's main process adds {@link #HEADER} to every request it makes to its own
 * server, carrying its version. The page cannot set or remove it, and a browser does not send
 * it. That is what lets an examination be held to the desktop app ({@code desktop_app_required}).
 *
 * <p><b>It is a claim, not proof.</b> The app is open source and its installers are unsigned, so
 * anybody determined can add the same header to a browser with an extension. What it stops is
 * the ordinary case: a candidate opening the paper in a browser, where the window can be left,
 * other sites opened and the clipboard used freely. A forged header is also not quiet: the page
 * still knows it is in a browser and says so, and a browser report arriving with the header is
 * flagged ({@code ExamEventService}). Kiosk mode on the lab's machines is what actually takes the
 * browser away.
 */
public final class DesktopClient {

    private DesktopClient() {}

    public static final String HEADER = "X-CPIntel-Desktop";

    /** Long enough for any version string; a header is not somewhere to store an essay. */
    private static final int MAX_LENGTH = 40;

    /**
     * The desktop app's version as the request states it, or null when the request did not come
     * from the desktop app. Only letters, digits, dots, hyphens and plus signs are kept, since it
     * ends up in the audit trail and on the invigilator's screen.
     */
    public static String version(HttpServletRequest request) {
        if (request == null) return null;
        String value = request.getHeader(HEADER);
        if (value == null) return null;
        String clean = value.strip().replaceAll("[^A-Za-z0-9.+-]", "");
        if (clean.isEmpty()) return null;
        return clean.length() > MAX_LENGTH ? clean.substring(0, MAX_LENGTH) : clean;
    }

    public static boolean isDesktop(HttpServletRequest request) {
        return version(request) != null;
    }
}
