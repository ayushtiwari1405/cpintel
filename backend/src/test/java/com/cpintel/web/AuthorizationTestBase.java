package com.cpintel.web;

import com.cpintel.config.AppMetrics;
import com.cpintel.config.CorsConfig;
import com.cpintel.config.SecurityConfig;
import com.cpintel.security.JwtAuthFilter;
import com.cpintel.security.JwtService;
import com.cpintel.security.RateLimitFilter;
import com.cpintel.security.RateLimitProperties;
import com.cpintel.security.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Shared wiring for the tests that pin down who may call what.
 *
 * <p>These load the real {@link SecurityConfig} — the filter chain rules, the method security
 * annotations and the role hierarchy that lets SUPER_ADMIN satisfy {@code hasRole('ADMIN')}.
 * Testing against a stand-in would prove only that the stand-in agrees with itself; the point
 * is that the configuration actually shipped refuses the requests it should.
 *
 * <p>The JWT filter is real but never sees a token, so it sets no authentication and leaves the
 * one the test installed alone. That is deliberate: it keeps these tests about authorisation —
 * what a given role may do — rather than about token parsing, which
 * {@code JwtRevocationTest} already covers.
 */
@Import({SecurityConfig.class, CorsConfig.class, JwtAuthFilter.class, RateLimitFilter.class})
public abstract class AuthorizationTestBase {

    protected static final long USER_ID = 42L;

    @Autowired
    protected MockMvc mvc;

    @MockBean protected JwtService jwtService;
    @MockBean protected RateLimitService rateLimitService;
    @MockBean protected RateLimitProperties rateLimitProperties;
    // The filters count throttles through this; the slice has no meter registry.
    @MockBean protected AppMetrics appMetrics;

    @BeforeEach
    void allowRateLimits() {
        // Throttling is tested on its own. Here it must never be the reason a request is
        // refused, or a 429 would masquerade as an authorisation result.
        when(rateLimitService.tryAcquire(anyString(), any(), any())).thenReturn(true);
        when(rateLimitProperties.getLogin()).thenReturn(rule());
        when(rateLimitProperties.getRecovery()).thenReturn(rule());
        when(rateLimitProperties.getRun()).thenReturn(rule());
        when(rateLimitProperties.getSync()).thenReturn(rule());
    }

    private RateLimitProperties.Rule rule() {
        return new RateLimitProperties.Rule(1000, java.time.Duration.ofMinutes(1));
    }

    /**
     * Signs the request in as the given role.
     *
     * The principal is the user id as a {@link Long}, matching what {@link JwtAuthFilter} puts
     * there in production — controllers read it with {@code @AuthenticationPrincipal Long}, so
     * a String here would compile and then fail at runtime for the wrong reason.
     */
    protected static RequestPostProcessor as(String role) {
        return SecurityMockMvcRequestPostProcessors.authentication(
            new UsernamePasswordAuthenticationToken(
                USER_ID, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    protected static RequestPostProcessor asUser()       { return as("USER"); }
    protected static RequestPostProcessor asAdmin()      { return as("ADMIN"); }
    protected static RequestPostProcessor asSuperAdmin() { return as("SUPER_ADMIN"); }
}
