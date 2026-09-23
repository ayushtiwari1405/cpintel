package com.cpintel.mail;

/**
 * What the handful of emails this system sends actually say.
 *
 * <p>Plain text first, HTML second, because the plain text is what a log line shows on a
 * deployment with no SMTP and what a terminal mail client renders — it has to stand on its own
 * rather than being a fallback nobody read.
 *
 * <p>Two rules run through all of them. They never carry a password, only a link that expires;
 * and they always say what to do if the recipient did not ask for this, because an unexpected
 * password-reset mail is the first sign somebody else is trying to get in.
 */
public final class EmailTemplates {

    private EmailTemplates() {}

    public record Message(String subject, String text, String html) {}

    public static Message passwordReset(String name, String link, long validMinutes) {
        String who = name == null || name.isBlank() ? "there" : name;
        String text = """
            Hello %s,

            Someone asked to reset the password on your CPIntel account. If it was you, open
            this link and choose a new one:

            %s

            The link stops working in %d minutes, and it can only be used once.

            If you did not ask for this, you do not need to do anything — your password has not
            changed. If you get these repeatedly, tell whoever administers your CPIntel
            deployment, because it means somebody else knows your address and is trying.

            — CPIntel
            """.formatted(who, link, validMinutes);

        String html = shell("Reset your password", """
            <p>Hello %s,</p>
            <p>Someone asked to reset the password on your CPIntel account. If it was you,
               choose a new one here:</p>
            <p><a class="button" href="%s">Set a new password</a></p>
            <p class="muted">The link stops working in %d minutes and can only be used once.</p>
            <p class="muted">If you did not ask for this, you do not need to do anything —
               your password has not changed. If it keeps happening, tell whoever administers
               your CPIntel deployment.</p>
            """.formatted(escape(who), escape(link), validMinutes));

        return new Message("Reset your CPIntel password", text, html);
    }

    /**
     * Sent after a password actually changes, to the address on the account.
     *
     * The point of this one is not the good news. It is that somebody whose account was taken
     * over finds out within seconds rather than the next time they try to sign in.
     */
    public static Message passwordChanged(String name, String how) {
        String who = name == null || name.isBlank() ? "there" : name;
        String text = """
            Hello %s,

            The password on your CPIntel account was just changed (%s). Every device that was
            signed in has been signed out.

            If this was not you, tell whoever administers your CPIntel deployment now — they
            can lock the account and set a new password.

            — CPIntel
            """.formatted(who, how);

        String html = shell("Your password was changed", """
            <p>Hello %s,</p>
            <p>The password on your CPIntel account was just changed (%s). Every device that
               was signed in has been signed out.</p>
            <p class="muted">If this was not you, tell whoever administers your CPIntel
               deployment now — they can lock the account and set a new password.</p>
            """.formatted(escape(who), escape(how)));

        return new Message("Your CPIntel password was changed", text, html);
    }

    /**
     * Sent when an administrator creates an account and chooses to mail the person about it.
     *
     * <p>It carries the username and a link, and never the password. The first password is
     * handed over by whoever set the account up; this exists so the person knows the account
     * is there and where to sign in, which is otherwise something they have to be told twice.
     */
    public static Message accountCreated(String name, String username, String signInUrl,
                                         boolean passwordSeparate) {
        String who = name == null || name.isBlank() ? "there" : name;
        String aboutPassword = passwordSeparate
            ? "Your first password was given to you separately — it is deliberately not in this "
              + "email. Once you are in, you can change it from your profile at any time."
            : "Use the Forgot password link on the sign-in page to set your password.";

        String text = """
            Hello %s,

            An account has been created for you on CPIntel.

            Username: %s
            Sign in:  %s

            %s

            — CPIntel
            """.formatted(who, username, signInUrl, aboutPassword);

        String html = shell("Your CPIntel account", """
            <p>Hello %s,</p>
            <p>An account has been created for you on CPIntel.</p>
            <p><strong>Username:</strong> %s</p>
            <p><a class="button" href="%s">Sign in to CPIntel</a></p>
            <p class="muted">%s</p>
            """.formatted(escape(who), escape(username), escape(signInUrl), escape(aboutPassword)));

        return new Message("Your CPIntel account", text, html);
    }

    /**
     * Sent when an administrator resets somebody's password for them.
     *
     * Carries no password and no link. Its only job is to tell the owner that it happened, so
     * an administrator quietly taking over an account is not something only the audit log
     * knows about.
     */
    public static Message passwordResetByAdmin(String name) {
        String who = name == null || name.isBlank() ? "there" : name;
        String text = """
            Hello %s,

            An administrator has set a new password on your CPIntel account, and every device
            that was signed in has been signed out. They will give you the new password
            directly — it is deliberately not in this email.

            If you did not expect this, ask them why before signing in.

            — CPIntel
            """.formatted(who);

        String html = shell("Your password was reset", """
            <p>Hello %s,</p>
            <p>An administrator has set a new password on your CPIntel account, and every
               device that was signed in has been signed out. They will give you the new
               password directly — it is deliberately not in this email.</p>
            <p class="muted">If you did not expect this, ask them why before signing in.</p>
            """.formatted(escape(who)));

        return new Message("Your CPIntel password was reset", text, html);
    }

    // ------------------------------------------------------------------ shell

    /** One inline-styled frame, because mail clients strip stylesheets. */
    private static String shell(String heading, String body) {
        return """
            <!doctype html>
            <html><body style="margin:0;padding:24px;background:#0b0d12;
              font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;">
              <table role="presentation" width="100%%" cellpadding="0" cellspacing="0">
                <tr><td align="center">
                  <table role="presentation" width="100%%" style="max-width:520px;
                    background:#11141b;border:1px solid #232733;border-radius:16px;padding:32px;">
                    <tr><td style="color:#e6e8ee;font-size:15px;line-height:1.6;">
                      <p style="margin:0 0 20px;font-size:13px;letter-spacing:.08em;
                         text-transform:uppercase;color:#8b93a7;">CPIntel</p>
                      <h1 style="margin:0 0 16px;font-size:20px;font-weight:600;color:#fff;">%s</h1>
                      %s
                    </td></tr>
                  </table>
                  <p style="max-width:520px;margin:16px auto 0;font-size:12px;color:#5b6273;">
                    Sent by your CPIntel deployment. Please do not reply to this address.
                  </p>
                </td></tr>
              </table>
            </body></html>
            """.formatted(escape(heading), body
                .replace("class=\"button\"",
                    "style=\"display:inline-block;background:#4f46e5;color:#fff;"
                    + "text-decoration:none;padding:11px 20px;border-radius:10px;"
                    + "font-weight:600;font-size:14px;\"")
                .replace("class=\"muted\"", "style=\"color:#8b93a7;font-size:13px;\""));
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
