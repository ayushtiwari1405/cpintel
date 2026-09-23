package com.cpintel.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.cpintel.config.AppMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Which requests this filter actually limits.
 *
 * <p>Worth its own test because the answer used to be "none of them". The rules were written as
 * {@code path.endsWith("/api/auth/login")} against a URI of {@code /api/v1/auth/login}, which
 * matches nothing, so every limit here was inert from the day the API was versioned — silently,
 * because an inert rate limiter looks exactly like one nobody is hitting.
 *
 * <p>So these assert the shape of the match rather than the behaviour behind it: that the
 * version prefix is stripped, that a route is compared exactly rather than by suffix, and that
 * requests nobody meant to limit still go straight through.
 */
class RateLimitRouteTest {

    private RateLimitService limiter;
    private RateLimitFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() throws Exception {
        limiter = mock(RateLimitService.class);
        chain = mock(FilterChain.class);
        when(limiter.tryAcquire(any(), any(), any())).thenReturn(true);

        filter = new RateLimitFilter(limiter, new RateLimitProperties(), mock(AppMetrics.class));
    }

    private void post(String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRemoteAddr("10.0.0.1");
        invoke(request);
    }

    private void invoke(HttpServletRequest request) throws Exception {
        ReflectionTestUtils.invokeMethod(filter, "doFilterInternal",
            request, new MockHttpServletResponse(), chain);
    }

    @Test
    @DisplayName("the versioned sign-in route is limited, which it was not before")
    void versionedLoginIsLimited() throws Exception {
        post("/api/v1/auth/login");

        verify(limiter).tryAcquire(eq(RateLimitFilter.LOGIN_IP), eq("10.0.0.1"), any());
    }

    @Test
    @DisplayName("the recovery routes share one bucket")
    void recoveryRoutesAreLimited() throws Exception {
        post("/api/v1/auth/forgot-password");
        post("/api/v1/auth/reset-password");
        post("/api/v1/auth/change-password");
        post("/api/v1/auth/register");

        verify(limiter, times(4))
            .tryAcquire(eq(RateLimitFilter.RECOVERY), eq("10.0.0.1"), any());
    }

    @Test
    @DisplayName("unlocking an examination is counted against the candidate, not the room")
    void examUnlockIsLimited() throws Exception {
        post("/api/v1/exams/7/unlock");

        // Keyed on the principal: a lab full of people sitting the same paper shares one
        // address, and one of them mistyping must not lock out the rest.
        verify(limiter).tryAcquire(eq(RateLimitFilter.EXAM_UNLOCK), isNull(), any());
    }

    @Test
    @DisplayName("an unversioned path still matches, so a rollback does not turn the limits off")
    void unversionedStillMatches() throws Exception {
        post("/api/auth/login");

        verify(limiter).tryAcquire(eq(RateLimitFilter.LOGIN_IP), any(), any());
    }

    @Test
    @DisplayName("routes are matched exactly, not by suffix")
    void noSuffixCollisions() throws Exception {
        // Would have matched an endsWith("/login") rule and spent somebody else's budget.
        post("/api/v1/admin/impersonate/login");
        post("/api/v1/practice/run");

        verifyNoInteractions(limiter);
        verify(chain, times(2)).doFilter(any(), any());
    }

    @Test
    @DisplayName("a GET is never limited here, whatever it addresses")
    void getsPassThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/login");
        invoke(request);

        verifyNoInteractions(limiter);
    }

    @Test
    @DisplayName("a context path in front of the API does not hide the route")
    void contextPathIsStripped() throws Exception {
        MockHttpServletRequest request =
            new MockHttpServletRequest("POST", "/cpintel/api/v1/auth/login");
        request.setContextPath("/cpintel");
        request.setRemoteAddr("10.0.0.1");
        invoke(request);

        verify(limiter).tryAcquire(eq(RateLimitFilter.LOGIN_IP), eq("10.0.0.1"), any());
    }
}
