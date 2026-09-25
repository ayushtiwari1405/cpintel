package com.cpintel.security;

import com.cpintel.entity.GroupContest;
import com.cpintel.events.ExamLockoutService;
import com.cpintel.repository.jpa.GroupContestRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** What an examination session may reach: its one paper, and nothing else. */
class ExamModeFilterTest {

    private ExamModeFilter filter;
    private ExamLockoutService lockout;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        GroupContestRepository events = mock(GroupContestRepository.class);
        when(events.findById(7L)).thenReturn(Optional.of(GroupContest.builder()
            .contestId(7L).kind("EXAM").platform("DOMJUDGE").externalId("midsem").build()));
        ObjectProvider<GroupContestRepository> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(events);
        lockout = mock(ExamLockoutService.class);
        when(lockout.lockFor(any())).thenReturn(Optional.empty());
        ObjectProvider<ExamLockoutService> lockoutProvider = mock(ObjectProvider.class);
        when(lockoutProvider.getIfAvailable()).thenReturn(lockout);
        filter = new ExamModeFilter(provider, lockoutProvider);
    }

    private int status(String method, String uri, Long examSession) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (examSession != null) request.setAttribute(SessionMode.EXAM_ATTRIBUTE, examSession);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        return response.getStatus();
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @DisplayName("an examination session reaches its paper and nothing else")
    @CsvSource({
        "GET,  /api/v1/exams/7,                              200",
        "POST, /api/v1/exams/7/events,                       200",
        "GET,  /api/v1/exams/8,                              403",
        "GET,  /api/v1/compete/DOMJUDGE/midsem/problems/A,   200",
        "POST, /api/v1/compete/DOMJUDGE/midsem/submit,       200",
        "GET,  /api/v1/compete/DOMJUDGE/other/problems/A,    403",
        "GET,  /api/v1/compete/CODEFORCES/2266,              403",
        "POST, /api/v1/run,                                  200",
        "GET,  /api/v1/users/me,                             200",
        "PUT,  /api/v1/users/me,                             403",
        "GET,  /api/v1/practice/problems,                    403",
        "GET,  /api/v1/files,                                403",
        "GET,  /api/v1/admin/users,                          403",
        "GET,  /api/v1/roadmaps/current,                     403",
        "POST, /api/v1/auth/logout,                          200",
    })
    void examSession(String method, String uri, int expected) throws Exception {
        assertEquals(expected, status(method.trim(), uri.trim(), 7L));
    }

    @ParameterizedTest(name = "ordinary session: {0} {1}")
    @DisplayName("an ordinary session is not this filter's business")
    @CsvSource({ "GET, /api/v1/practice/problems", "GET, /api/v1/admin/users" })
    void ordinarySession(String method, String uri) throws Exception {
        assertEquals(200, status(method.trim(), uri.trim(), null));
    }

    private void signedInAs(long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            userId, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    @DisplayName("an ordinary session of a candidate reaches nothing while their examination runs")
    void ordinarySessionHeldDuringExamination() throws Exception {
        when(lockout.lockFor(42L)).thenReturn(Optional.of(new ExamLockoutService.Lock(7L,
            "Midsem", Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600),
            Set.of(42L))));

        signedInAs(42L, "USER");
        assertEquals(401, status("GET", "/api/v1/practice/problems", null));
        assertEquals(401, status("GET", "/api/v1/users/me", null));
        assertEquals(200, status("POST", "/api/v1/auth/logout", null));

        // Their examination session is the way in, and is held only to its paper.
        assertEquals(200, status("GET", "/api/v1/exams/7", 7L));

        // Somebody else, and an admin, carry on.
        signedInAs(43L, "USER");
        assertEquals(200, status("GET", "/api/v1/practice/problems", null));
        signedInAs(42L, "ADMIN");
        assertEquals(200, status("GET", "/api/v1/practice/problems", null));
    }
}
