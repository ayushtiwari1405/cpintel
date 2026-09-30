package com.cpintel.classrooms;

import com.cpintel.entity.Classroom;
import com.cpintel.exception.ApiException;
import com.cpintel.integration.domjudge.DomjudgeCredentialStore;
import com.cpintel.integration.domjudge.DomjudgeJudges;
import com.cpintel.repository.jpa.ClassroomMemberRepository;
import com.cpintel.repository.jpa.ClassroomRepository;
import com.cpintel.repository.jpa.ContestGroupRepository;
import com.cpintel.repository.jpa.GroupContestRepository;
import com.cpintel.repository.jpa.GroupMemberRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.service.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Who runs a classroom, and what can never change about one once people are in it.
 */
class ClassroomServiceTest {

    private static final Long OWNER = 1L;
    private static final Long OTHER_ADMIN = 2L;
    private static final Long ROOM = 10L;
    private static final String URL = "https://judge.example.edu";

    private ClassroomRepository classrooms;
    private ClassroomMemberRepository members;
    private GroupContestRepository events;
    private DomjudgeJudges judges;
    private ClassroomService service;
    private Classroom room;

    @BeforeEach
    void setUp() {
        classrooms = mock(ClassroomRepository.class);
        members = mock(ClassroomMemberRepository.class);
        events = mock(GroupContestRepository.class);
        judges = mock(DomjudgeJudges.class);
        DomjudgeCredentialStore credentials = mock(DomjudgeCredentialStore.class);
        when(credentials.seal(anyString())).thenAnswer(c -> "sealed:" + c.getArgument(0));
        when(credentials.open(anyString())).thenAnswer(c ->
            c.<String>getArgument(0).replace("sealed:", ""));

        service = new ClassroomService(classrooms, members, mock(ContestGroupRepository.class),
            events, mock(GroupMemberRepository.class), mock(UserRepository.class), credentials,
            judges, mock(AuditService.class));

        room = Classroom.builder().classroomId(ROOM).name("DSA").domjudgeUrl(URL)
            .ownerId(OWNER).isActive(true).build();
        when(classrooms.findById(ROOM)).thenReturn(Optional.of(room));
        when(classrooms.save(any())).thenAnswer(c -> c.getArgument(0));
        signIn("ROLE_ADMIN");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void signIn(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(OWNER, null,
                java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList()));
    }

    private ClassroomsDto.ClassroomRequest request(String url) {
        return new ClassroomsDto.ClassroomRequest("DSA", null, url, null, null);
    }

    @Nested
    @DisplayName("judge addresses")
    class Urls {

        @Test
        @DisplayName("are stored in one canonical form, so uniqueness means something")
        void normalised() {
            assertEquals(URL, ClassroomService.normaliseUrl(" https://judge.example.edu/ "));
            assertEquals(URL, ClassroomService.normaliseUrl("https://judge.example.edu/api/v4"));
            assertEquals(URL, ClassroomService.normaliseUrl("https://judge.example.edu/api/"));
            assertEquals("http://10.0.0.5:12345/domjudge",
                ClassroomService.normaliseUrl("http://10.0.0.5:12345/domjudge/"));
        }

        @Test
        @DisplayName("that are not web addresses are refused")
        void refused() {
            assertThrows(ApiException.class, () -> ClassroomService.normaliseUrl("judge"));
            assertThrows(ApiException.class, () -> ClassroomService.normaliseUrl("ftp://x.edu"));
        }

        @Test
        @DisplayName("belong to one classroom only")
        void unique() {
            when(classrooms.findByDomjudgeUrl(URL)).thenReturn(Optional.of(room));
            assertThrows(ApiException.class,
                () -> service.create(OTHER_ADMIN, request(URL + "/"), null));
            verify(judges, never()).check(any(), any(), any());
        }

        @Test
        @DisplayName("are checked against the judge before anything is saved")
        void checkedFirst() {
            when(classrooms.findByDomjudgeUrl(anyString())).thenReturn(Optional.empty());
            when(judges.check(eq("https://other.example.edu"), any(), any()))
                .thenThrow(ApiException.badRequest("Could not reach DOMjudge"));

            assertThrows(ApiException.class,
                () -> service.create(OWNER, request("https://other.example.edu"), null));
            verify(classrooms, never()).save(any());
        }
    }

    @Nested
    @DisplayName("who runs a classroom")
    class Access {

        @Test
        @DisplayName("its owner does")
        void owner() {
            assertSame(room, service.requireManaged(OWNER, ROOM));
        }

        @Test
        @DisplayName("another admin does not, unless added as staff")
        void otherAdmin() {
            assertThrows(ApiException.class, () -> service.requireManaged(OTHER_ADMIN, ROOM));

            when(classrooms.isStaff(ROOM, OTHER_ADMIN)).thenReturn(true);
            assertSame(room, service.requireManaged(OTHER_ADMIN, ROOM));
        }

        @Test
        @DisplayName("a superadmin runs every classroom")
        void superAdmin() {
            signIn("ROLE_SUPER_ADMIN");
            assertSame(room, service.requireManaged(OTHER_ADMIN, ROOM));
            assertNull(service.managedIds(OTHER_ADMIN), "null means every classroom");
        }

        @Test
        @DisplayName("an admin lists only the classrooms they run")
        void listScoped() {
            when(classrooms.findManagedBy(OTHER_ADMIN)).thenReturn(List.of());
            assertTrue(service.list(OTHER_ADMIN).isEmpty());
            verify(classrooms, never()).findAllByOrderByNameAsc();
        }
    }

    @Nested
    @DisplayName("changing a classroom")
    class Updates {

        @Test
        @DisplayName("cannot point it at another judge once students are in it")
        void judgeFixedOnceUsed() {
            when(classrooms.findByDomjudgeUrl(anyString())).thenReturn(Optional.empty());
            when(members.countByClassroomId(ROOM)).thenReturn(30L);

            assertThrows(ApiException.class,
                () -> service.update(OWNER, ROOM, request("https://other.example.edu"), null));
        }

        @Test
        @DisplayName("reconnects to the judge after a change of settings")
        void evictsClient() {
            when(classrooms.findByDomjudgeUrl(URL)).thenReturn(Optional.of(room));

            service.update(OWNER, ROOM, request(URL), null);

            verify(judges).evict(ROOM);
        }

        @Test
        @DisplayName("keeps the stored service password when none is sent")
        void keepsPassword() {
            room.setServiceUsername("svc");
            room.setServicePassword("sealed:secret");
            when(classrooms.findByDomjudgeUrl(URL)).thenReturn(Optional.of(room));

            service.update(OWNER, ROOM,
                new ClassroomsDto.ClassroomRequest("DSA", null, URL, "svc", null), null);

            assertEquals("sealed:secret", room.getServicePassword());
            verify(judges).check(URL, "svc", "secret");
        }

        @Test
        @DisplayName("an empty password clears the service account")
        void clearsServiceAccount() {
            room.setServiceUsername("svc");
            room.setServicePassword("sealed:secret");
            when(classrooms.findByDomjudgeUrl(URL)).thenReturn(Optional.of(room));

            service.update(OWNER, ROOM,
                new ClassroomsDto.ClassroomRequest("DSA", null, URL, "svc", ""), null);

            assertNull(room.getServiceUsername());
            assertNull(room.getServicePassword());
        }
    }
}
