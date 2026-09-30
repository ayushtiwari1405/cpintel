package com.cpintel.classrooms;

import com.cpintel.entity.ContestGroup;
import com.cpintel.entity.GroupContest;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.ContestGroupRepository;
import com.cpintel.repository.jpa.GroupContestRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Opening a team, event or classroom by id is where the gap was: the lists were filtered, the
 * ids were not. Every admin route with one of these ids in its path goes through this check.
 */
class ClassroomAccessInterceptorTest {

    private static final Long ADMIN = 5L;

    private ClassroomService classrooms;
    private ClassroomAccessInterceptor interceptor;

    @BeforeEach
    void setUp() {
        classrooms = mock(ClassroomService.class);
        ContestGroupRepository groups = mock(ContestGroupRepository.class);
        GroupContestRepository events = mock(GroupContestRepository.class);
        when(groups.findById(7L)).thenReturn(Optional.of(
            ContestGroup.builder().groupId(7L).classroomId(2L).build()));
        when(events.findById(9L)).thenReturn(Optional.of(
            GroupContest.builder().contestId(9L).classroomId(3L).build()));
        // Classroom 1 is the only one this admin runs.
        when(classrooms.requireManaged(eq(ADMIN), argThat(id -> id != 1L)))
            .thenThrow(ApiException.forbidden("not yours"));
        interceptor = new ClassroomAccessInterceptor(classrooms, groups, events);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(ADMIN, null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private boolean call(String path, Map<String, String> vars) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, vars);
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    @Test
    @DisplayName("a classroom the admin runs opens")
    void ownClassroom() throws Exception {
        assertTrue(call("/api/v1/admin/classrooms/1", Map.of("classroomId", "1")));
    }

    @Test
    @DisplayName("another classroom does not")
    void otherClassroom() {
        assertThrows(ApiException.class,
            () -> call("/api/v1/admin/classrooms/2/members", Map.of("classroomId", "2")));
    }

    @Test
    @DisplayName("a team is checked against its classroom")
    void team() {
        assertThrows(ApiException.class,
            () -> call("/api/v1/admin/groups/7", Map.of("groupId", "7")));
    }

    @Test
    @DisplayName("an event is checked against its classroom, under either name")
    void event() {
        assertThrows(ApiException.class,
            () -> call("/api/v1/admin/events/9/passwords", Map.of("eventId", "9")));
        assertThrows(ApiException.class,
            () -> call("/api/v1/admin/groups/contests/9/standings", Map.of("contestId", "9")));
    }

    @Test
    @DisplayName("a DOMjudge file rule is checked against the classroom its id names")
    void fileRule() throws Exception {
        assertThrows(ApiException.class, () -> call("/api/v1/admin/contest-files/DOMJUDGE/4~demo",
            Map.of("platform", "DOMJUDGE", "contestId", "4~demo")));
        assertTrue(call("/api/v1/admin/contest-files/CODEFORCES/2259",
            Map.of("platform", "CODEFORCES", "contestId", "2259")));
    }

    @Test
    @DisplayName("an account is only reachable by the admins of a classroom it is in")
    void userRoutes() {
        doThrow(ApiException.notFound("No such user"))
            .when(classrooms).requireVisibleUser(ADMIN, 12L);
        assertThrows(ApiException.class,
            () -> call("/api/v1/admin/users/12/password", Map.of("userId", "12")));
    }

    @Test
    @DisplayName("a user id elsewhere is not an account route")
    void userIdElsewhere() throws Exception {
        assertTrue(call("/api/v1/admin/classrooms/1/members/12",
            Map.of("classroomId", "1", "userId", "12")));
        verify(classrooms, never()).requireVisibleUser(any(), any());
    }

    @Test
    @DisplayName("routes without these ids are left alone")
    void unrelated() throws Exception {
        assertTrue(call("/api/v1/admin/contest-files/default", Map.of("platform", "CODEFORCES")));
        verifyNoInteractions(classrooms);
    }
}
