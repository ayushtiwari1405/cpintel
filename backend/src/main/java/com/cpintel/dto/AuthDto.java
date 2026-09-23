package com.cpintel.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.*;
import lombok.*;
import lombok.extern.jackson.Jacksonized;

public class AuthDto {

    @Getter @Setter
    public static class RegisterRequest {
        @NotBlank(message = "Username is required")
        @Size(min = 3, max = 50, message = "Username must be 3-50 characters")
        @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "Username can only contain letters, numbers and underscores")
        private String username;

        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        private String email;

        @NotBlank(message = "Password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        private String password;

        @Size(max = 100)
        private String fullName;
    }

    /**
     * Signing in with an email address or a username.
     *
     * <p>One field for both, rather than two fields or a mode switch. People are given a
     * username and a first password when their account is made, and the address on the account
     * may well be one they never use — so insisting on the address at the sign-in box asks half
     * of them for something they do not have to hand. Which of the two was typed is worked out
     * by looking, in {@code AuthService}, and neither form is treated as more authoritative
     * than the other.
     *
     * <p>{@code email} is accepted as an alias so that a client built against the older shape
     * keeps working. It is not validated as an address any more, precisely because a username
     * is now allowed to arrive in it.
     */
    @Getter @Setter
    public static class LoginRequest {
        @NotBlank(message = "An email address or username is required")
        @Size(max = 255)
        @JsonAlias({"email", "username"})
        private String identifier;

        @NotBlank(message = "Password is required")
        private String password;
    }

    @Getter @Builder @Jacksonized
    public static class AuthResponse {
        private String accessToken;
        private String refreshToken;
        private UserDto.Profile user;
    }

    @Getter @Setter
    public static class RefreshRequest {
        @NotBlank
        private String refreshToken;
    }

    @Getter @Setter
    public static class ForgotPasswordRequest {
        @NotBlank @Email
        private String email;
    }

    @Getter @Setter
    public static class ResetPasswordRequest {
        @NotBlank(message = "The reset link is missing its token")
        private String token;

        @NotBlank(message = "A new password is required")
        @Size(min = 8, max = 200, message = "A password has to be at least 8 characters")
        private String newPassword;
    }

    /** Changing your own password while signed in, which needs the current one. */
    @Getter @Setter
    public static class ChangePasswordRequest {
        @NotBlank(message = "Your current password is required")
        private String currentPassword;

        @NotBlank(message = "A new password is required")
        @Size(min = 8, max = 200, message = "A password has to be at least 8 characters")
        private String newPassword;
    }
}
