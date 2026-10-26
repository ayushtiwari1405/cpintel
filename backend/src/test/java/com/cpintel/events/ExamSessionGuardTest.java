package com.cpintel.events;

import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.ClassroomMemberRepository;
import com.cpintel.repository.jpa.GroupContestRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.cpintel.entity.GroupContest;
import com.cpintel.security.SessionMode;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A classroom's DOMjudge contests are its students' alone. With a service account on the
 * judge, nothing else would stop someone reading another classroom's statements before its
 * paper just by typing the qualified id.
 */
class ExamSessionGuardTest {

    private static final Long USER = 42L;

    private static final Long PAPER = 7L;

    private GroupContestRepository events;
    private ExamAccessService access;
    private ClassroomMemberRepository members;
    private ExamSessionGuard guard;

    @BeforeEach
    void setUp() {
        events = mock(GroupContestRepository.class);
        when(events.findByPlatformAndExternalId(anyString(), anyString())).thenReturn(List.of());
        members = mock(ClassroomMemberRepository.class);
        access = mock(ExamAccessService.class);
        guard = new ExamSessionGuard(events, access, members);
        signIn("ROLE_USER");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    /** An examination session for a paper on 3~demo, starting at the given offset from now. */
    private GroupContest examSession(Duration fromNow) {
        GroupContest paper = GroupContest.builder().contestId(PAPER).kind("EXAM")
            .platform("DOMJUDGE").externalId("3~demo").name("Midsem").lifecycle("SCHEDULED")
            .startsAt(Instant.now().plus(fromNow))
            .endsAt(Instant.now().plus(fromNow).plus(Duration.ofHours(2))).build();
        when(events.findById(PAPER)).thenReturn(Optional.of(paper));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(SessionMode.EXAM_ATTRIBUTE, PAPER);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return paper;
    }

    private void signIn(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            USER, null, List.of(new SimpleGrantedAuthority(role))));
    }

    @Test
    @DisplayName("a student opens their own classroom's contest")
    void member() {
        when(members.existsByClassroomIdAndUserUserId(3L, USER)).thenReturn(true);
        assertDoesNotThrow(() -> guard.requireContestAccess(USER, "DOMJUDGE", "3~demo"));
    }

    @Test
    @DisplayName("and not another classroom's")
    void nonMember() {
        when(members.existsByClassroomIdAndUserUserId(4L, USER)).thenReturn(false);
        assertThrows(ApiException.class,
            () -> guard.requireContestAccess(USER, "DOMJUDGE", "4~demo"));
    }

    @Test
    @DisplayName("Codeforces contests belong to no classroom")
    void codeforces() {
        assertDoesNotThrow(() -> guard.requireContestAccess(USER, "CODEFORCES", "2259"));
        verifyNoInteractions(members);
    }

    @Test
    @DisplayName("admins are not held to it")
    void admin() {
        signIn("ROLE_ADMIN");
        assertDoesNotThrow(() -> guard.requireContestAccess(USER, "DOMJUDGE", "4~demo"));
    }

    @Test
    @DisplayName("an examination session waits for the start without reading the paper")
    void paperWithheldBeforeStart() {
        examSession(Duration.ofMinutes(30));

        // The countdown is still served.
        assertDoesNotThrow(() -> guard.requireContestAccess(USER, "DOMJUDGE", "3~demo"));
        ApiException e = assertThrows(ApiException.class,
            () -> guard.requirePaperContent(USER, "DOMJUDGE", "3~demo"));
        assertTrue(e.getMessage().contains("has not started"));
    }

    @Test
    @DisplayName("once the paper starts, its content still needs the room's unlock")
    void paperNeedsUnlockOnceRunning() {
        GroupContest paper = examSession(Duration.ofMinutes(-10));

        assertDoesNotThrow(() -> guard.requirePaperContent(USER, "DOMJUDGE", "3~demo"));
        verify(access, atLeastOnce()).requireUnlocked(paper, USER);
    }

    @Test
    @DisplayName("an ordinary session's reads are not changed by the paper gate")
    void ordinarySessionUnchanged() {
        when(members.existsByClassroomIdAndUserUserId(3L, USER)).thenReturn(true);
        assertDoesNotThrow(() -> guard.requirePaperContent(USER, "DOMJUDGE", "3~demo"));
    }
}
