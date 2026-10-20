package com.cpintel.web;

import com.cpintel.controller.AuthController;
import com.cpintel.service.AuthService;
import com.cpintel.service.PasswordService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The allowlist as the shipped Spring Security chain applies it, on the endpoint that matters:
 * refresh answers with an access token, so it is what a foreign page would want to read.
 *
 * <p>One origin is configured so that both directions are visible in the same chain.
 */
@WebMvcTest(controllers = AuthController.class)
@TestPropertySource(properties = "cpintel.cors.allowed-origins=https://other.example.edu")
class CorsFilterChainTest extends AuthorizationTestBase {

    private static final String REFRESH = "/api/v1/auth/refresh";

    @MockBean private AuthService authService;
    @MockBean private PasswordService passwordService;
    @MockBean private com.cpintel.security.JwtProperties jwtProperties;

    @Test
    @DisplayName("a foreign origin is refused before the controller runs")
    void foreignRefusedBeforeController() throws Exception {
        mvc.perform(post(REFRESH).header(HttpHeaders.ORIGIN, "https://evil.example"))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        verify(authService, never()).refresh(any(), any());
    }

    @Test
    @DisplayName("same origin reaches the controller, which answers 401 for a missing session")
    void sameOriginReachesController() throws Exception {
        mvc.perform(post(REFRESH)
                .header(HttpHeaders.HOST, "localhost")
                .header(HttpHeaders.ORIGIN, "http://localhost"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("the listed origin gets its own origin back, with credentials")
    void listedOriginAllowed() throws Exception {
        mvc.perform(post(REFRESH).header(HttpHeaders.ORIGIN, "https://other.example.edu"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://other.example.edu"))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    @DisplayName("no Origin header: not a CORS request at all")
    void noOrigin() throws Exception {
        mvc.perform(post(REFRESH))
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
