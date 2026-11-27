package com.cpintel.common;

/**
 * What an address has to look like before an account may be given it.
 *
 * <p>Bean validation's own {@code @Email} accepts {@code name@localhost}, which no public mail
 * server can send a reset link to, and which the console's own form refuses. The form, the roster
 * import and the API now agree. Sign-in does not apply it, so older accounts keep working.
 */
public final class EmailRule {

    public static final String PATTERN = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";
    public static final String MESSAGE = "Enter an email address such as name@example.com";
    public static final int MAX_LENGTH = 255;

    private EmailRule() {}
}
