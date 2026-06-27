package com.cpintel.dto;

import jakarta.validation.constraints.*;
import lombok.*;

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

    @Getter @Setter
    public static class LoginRequest {
        @NotBlank(message = "Email is required")
        @Email
        private String email;

        @NotBlank(message = "Password is required")
        private String password;
    }

    @Getter @Builder
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
        @NotBlank
        private String token;

        @NotBlank
        @Size(min = 8)
        private String newPassword;
    }
}
