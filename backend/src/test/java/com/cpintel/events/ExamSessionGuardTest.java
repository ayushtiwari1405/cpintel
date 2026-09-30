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

import java.util.List;

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

    private ClassroomMemberRepository members;
    private ExamSessionGuard guard;

    @BeforeEach
    void setUp() {
        GroupContestRepository events = mock(GroupContestRepository.class);
        when(events.findByPlatformAndExternalId(anyString(), anyString())).thenReturn(List.of());
        members = mock(ClassroomMemberRepository.class);
        guard = new ExamSessionGuard(events, mock(ExamAccessService.class), members);
        signIn("ROLE_USER");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
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
}
