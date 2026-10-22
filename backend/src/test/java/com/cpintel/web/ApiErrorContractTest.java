package com.cpintel.web;

import com.cpintel.controller.AuthController;
import com.cpintel.controller.UserController;
import com.cpintel.exception.ApiException;
import com.cpintel.service.AuthService;
import com.cpintel.service.PasswordService;
import com.cpintel.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a client is told when its request goes wrong, through the real controllers, security
 * chain and GlobalExceptionHandler.
 *
 * <p>Two things are pinned: the status and {@code code} a client can act on, and what the body
 * must never carry - a stack trace, a parser's or a database's own words, or anything the
 * caller sent that is a secret (a password, a reset token).
 */
@WebMvcTest(controllers = { AuthController.class, UserController.class })
class ApiErrorContractTest extends AuthorizationTestBase {

    @MockBean private AuthService authService;
    @MockBean private PasswordService passwordService;
    @MockBean private UserService userService;
    @MockBean private com.cpintel.security.JwtProperties jwtProperties;

    private static final String SECRET_PASSWORD = "hunter2-do-not-echo";
    private static final String RESET_TOKEN = "reset-token-do-not-echo";

    /** Words that mean a server's internals reached the client. */
    private static final List<String> INTERNALS = List.of("Exception", "\tat ", "com.cpintel", "jackson",
        "Jackson", "JSON parse", "SQL", "org.springframework", "java.", "stack", "Caused by");

    @BeforeEach
    void refreshLifetime() {
        when(jwtProperties.getRefreshExpiryMs()).thenReturn(604_800_000L);
    }

    private static void assertSafe(MvcResult result, String... alsoForbidden) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (String word : INTERNALS) {
            assertFalse(body.contains(word), "the error body exposes \"" + word + "\"");
        }
        for (String word : alsoForbidden) {
            assertFalse(body.contains(word), "the error body echoes a value it must not");
        }
    }

    @Test
    @DisplayName("a body that is not JSON is a 400 BAD_REQUEST that names no parser")
    void malformedJson() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"ada\",\"password\":\"" + SECRET_PASSWORD + "\""))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andReturn();
        assertSafe(r, SECRET_PASSWORD);
        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("missing fields are a 400 VALIDATION_ERROR naming each field, and the service is not called")
    void missingFields() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.errors.identifier").exists())
            .andExpect(jsonPath("$.errors.password").exists())
            .andReturn();
        assertSafe(r);
        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("a rejected value is named by its field, never repeated back")
    void rejectedValueNotEchoed() throws Exception {
        String tooLong = "x".repeat(256);
        MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + tooLong + "\",\"password\":\"" + SECRET_PASSWORD + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.identifier").exists())
            .andReturn();
        assertSafe(r, SECRET_PASSWORD, tooLong);

        r = mvc.perform(post("/api/v1/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + RESET_TOKEN + "\",\"newPassword\":\"short\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.newPassword").value("A password has to be at least 8 characters"))
            .andReturn();
        assertSafe(r, RESET_TOKEN, "short\"");
        verifyNoInteractions(passwordService);
    }

    @Test
    @DisplayName("an expired reset link is reported as such, without the token")
    void expiredResetToken() throws Exception {
        doThrow(ApiException.badRequest("That reset link has expired. Ask for a new one from the sign-in page."))
            .when(passwordService).completeReset(anyString(), anyString(), any());

        MvcResult r = mvc.perform(post("/api/v1/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + RESET_TOKEN + "\",\"newPassword\":\"" + SECRET_PASSWORD + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("That reset link has expired. Ask for a new one from the sign-in page."))
            .andReturn();
        assertSafe(r, RESET_TOKEN, SECRET_PASSWORD);
    }

    @Test
    @DisplayName("the wrong content type is a 415 and the wrong method a 405")
    void wrongTypeOrMethod() throws Exception {
        assertSafe(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.TEXT_PLAIN).content("ada"))
            .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andReturn());
        // Signed in, so the security chain lets it through to where the method is checked.
        assertSafe(mvc.perform(get("/api/v1/auth/login").with(asUser()))
            .andExpect(status().isMethodNotAllowed()).andReturn());
    }

    @Test
    @DisplayName("an unknown path is a 404 for a signed-in user and a 401 for anyone else")
    void unknownPath() throws Exception {
        assertSafe(mvc.perform(get("/api/v1/no-such-thing").with(asUser()))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND")).andReturn());
        mvc.perform(get("/api/v1/no-such-thing")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("a failure inside the server is a 500 that says nothing about what failed")
    void internalFailure() throws Exception {
        when(userService.getProfile(USER_ID)).thenThrow(new DataAccessResourceFailureException(
            "Connection to postgres:5432 refused; password authentication failed for user cpintel"));

        MvcResult r = mvc.perform(get("/api/v1/users/me").with(asUser()))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
            .andReturn();
        assertSafe(r, "postgres", "password authentication", "cpintel");
    }

    @Test
    @DisplayName("a profile update over a field's limit is refused before the service sees it")
    void profileFieldTooLong() throws Exception {
        mvc.perform(put("/api/v1/users/me").with(asUser()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"" + "a".repeat(101) + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.errors.fullName").exists());
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("a refused sign-in says only that the credentials were wrong")
    void badCredentials() throws Exception {
        when(authService.login(any(), any())).thenThrow(
            new org.springframework.security.authentication.BadCredentialsException("user ada not found in table users"));

        MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"ada\",\"password\":\"" + SECRET_PASSWORD + "\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
            .andReturn();
        assertSafe(r, SECRET_PASSWORD, "not found in table");
    }
}
