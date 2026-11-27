package com.cpintel.mail;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Passwords sent by mail, as an admin drafted them.
 *
 * <p>The message arrives as lines of styled text, never as markup: the HTML that is mailed is
 * written here, with everything in it escaped. No recipient address is accepted from the
 * client — each message names an account, and goes to the address on it.
 */
public final class PasswordMailDto {

    /** Messages in one request. Small, so a slow mail server answers before the request times out. */
    public static final int BATCH = 5;

    private PasswordMailDto() {}

    public enum Style { REGULAR, BOLD, PASSWORD }

    public record Span(@NotNull @Size(max = 2000) String text, @NotNull Style style) {
        @Override
        public String toString() {
            return "Span[" + style + "]";
        }
    }

    public record Line(boolean heading,
                       @NotNull @Size(max = 100) List<@NotNull @Valid Span> spans) {}

    public record Message(
        @NotNull Long userId,
        /** The password the message carries, checked to be the account's before anything goes. */
        @NotBlank @Size(max = 200) String password,
        @NotBlank @Size(max = 200)
        @Pattern(regexp = "[^\\r\\n]*", message = "A subject is a single line")
        String subject,
        @NotEmpty @Size(max = 200) List<@NotNull @Valid Line> lines
    ) {
        /** Without the password or the text, either of which would put it into a log. */
        @Override
        public String toString() {
            return "Message[userId=" + userId + "]";
        }
    }

    public record Request(@NotEmpty @Size(max = BATCH) List<@NotNull @Valid Message> messages) {}

    /** {@code sent} means the mail server accepted it, which is as far as CPIntel can see. */
    public record Result(Long userId, boolean sent, String error) {}

    public record Status(boolean configured, String from) {}
}
