package com.cpintel.web;

import com.cpintel.controller.AuthController;
import com.cpintel.dto.AuthDto;
import com.cpintel.exception.ApiException;
import com.cpintel.service.AuthService;
import com.cpintel.service.PasswordService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The public edge of the API: which endpoints an unauthenticated caller may reach at all, and
 * what happens at the one that is deliberately closed.
 */
@WebMvcTest(controllers = AuthController.class)
class AuthEndpointAuthorizationTest extends AuthorizationTestBase {

    @MockBean private AuthService authService;
    @MockBean private PasswordService passwordService;

    private static final String REGISTER = """
        {"username":"someone","email":"someone@example.com","password":"password123"}
        """;
    private static final String LOGIN = """
        {"email":"someone@example.com","password":"password123"}
        """;

    @Test
    @DisplayName("registration is reachable without a token but refused while sign-up is closed")
    void registrationClosed() throws Exception {
        // Reaching the service at all is the point: the refusal is a policy decision inside
        // AuthService, not the filter chain turning an anonymous caller away.
        when(authService.register(any(), any()))
            .thenThrow(ApiException.forbidden(
                "Registration is closed. Ask an administrator to create your account."));

        mvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON).content(REGISTER))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value(
                org.hamcrest.Matchers.containsString("Registration is closed")));

        verify(authService).register(any(), any());
    }

    @Test
    @DisplayName("login is reachable without a token")
    void loginIsPublic() throws Exception {
        when(authService.login(any(), any())).thenReturn(
            AuthDto.AuthResponse.builder().accessToken("t").refreshToken("r").build());

        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(LOGIN))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a throttled sign-in answers 429 and says how long to wait")
    void throttledLoginCarriesRetryAfter() throws Exception {
        when(authService.login(any(), any()))
            .thenThrow(ApiException.tooManyRequests(
                "Too many sign-in attempts for this account. Try again in 240 seconds."));

        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(LOGIN))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "240"));
    }

    @Test
    @DisplayName("logout still requires a token — it revokes something, so it must know whose")
    void logoutRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/v1/auth/logout"))
            .andExpect(status().isUnauthorized());
    }
}
