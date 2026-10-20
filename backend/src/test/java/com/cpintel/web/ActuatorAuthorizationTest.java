package com.cpintel.web;

import com.cpintel.controller.ApiVersionController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who the filter chain lets through to the actuator.
 *
 * <p>The slice maps no actuator endpoints, so a request the chain lets past ends in 404. That
 * is the signal used here: 404 means "security allowed it", 401 and 403 mean it did not. The
 * controller is only there to give the slice something to start; it has no dependencies.
 */
@WebMvcTest(controllers = ApiVersionController.class)
class ActuatorAuthorizationTest extends AuthorizationTestBase {

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/prometheus"})
    @DisplayName("health and the Prometheus scrape stay open: Docker, deploy.sh and monitoring call them unauthenticated")
    void operationalEndpointsStayOpen(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("metrics asks an anonymous caller to authenticate")
    void anonymousMetrics() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    @DisplayName("metrics is refused to students and classroom admins")
    void metricsRefused(String role) throws Exception {
        mvc.perform(get("/actuator/metrics").with(as(role))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics/jvm.memory.used").with(as(role)))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("metrics is the super admin's")
    void metricsForSuperAdmin() throws Exception {
        mvc.perform(get("/actuator/metrics").with(asSuperAdmin()))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an actuator endpoint added later is closed by default, not open")
    void unknownActuatorEndpointClosed() throws Exception {
        mvc.perform(get("/actuator/env").with(asUser())).andExpect(status().isForbidden());
    }
}
