package com.cpintel.evaluation;

import com.cpintel.classrooms.ClassroomService;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.ClassroomRepository;
import com.cpintel.repository.jpa.ExamTaAssignmentRepository;
import com.cpintel.repository.jpa.UnifiedScoreRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Adding a TA follows the roster's account rules: link what exists, create what doesn't. */
class ClassroomTaServiceTest {

    private static final long ADMIN = 1L;
    private static final long ROOM = 5L;

    private final ClassroomRepository classrooms = mock(ClassroomRepository.class);
    private final ExamTaAssignmentRepository assignments = mock(ExamTaAssignmentRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private ClassroomTaService service;

    @BeforeEach
    void setUp() {
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("hash");
        service = new ClassroomTaService(mock(ClassroomService.class), classrooms, assignments,
            users, mock(UnifiedScoreRepository.class), encoder, mock(AuditService.class));
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());
        when(users.findByUsername(anyString())).thenReturn(Optional.empty());
        when(users.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setUserId(77L);
            return u;
        });
        when(classrooms.addTa(anyLong(), anyLong(), anyLong())).thenReturn(1);
    }

    @Test
    @DisplayName("An existing account is found by email, then username, and linked as it is")
    void linksExisting() {
        User existing = User.builder().userId(3L).username("asha").email("asha@x.edu")
            .role("USER").isActive(true).build();
        when(users.findByUsername("asha")).thenReturn(Optional.of(existing));

        EvaluationDto.TaAdded added = service.add(ADMIN, ROOM,
            new EvaluationDto.TaRequest(null, "asha", null), null);

        assertFalse(added.created());
        assertNull(added.password());
        verify(users, never()).save(any());
        verify(classrooms).addTa(ROOM, 3L, ADMIN);
    }

    @Test
    @DisplayName("Admins are not made TAs")
    void refusesAdmins() {
        when(users.findByEmail("boss@x.edu")).thenReturn(Optional.of(User.builder().userId(2L)
            .username("boss").role("ADMIN").isActive(true).build()));

        assertThrows(ApiException.class, () -> service.add(ADMIN, ROOM,
            new EvaluationDto.TaRequest("Boss@X.edu", null, null), null));
        verify(classrooms, never()).addTa(anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("Nobody matching: a new USER account with a generated password, shown once")
    void createsNew() {
        EvaluationDto.TaAdded added = service.add(ADMIN, ROOM,
            new EvaluationDto.TaRequest("new.ta@x.edu", null, "New TA"), null);

        assertTrue(added.created());
        assertNotNull(added.password());
        assertEquals("newta", added.ta().username());
        verify(classrooms).addTa(ROOM, 77L, ADMIN);
    }

    @Test
    @DisplayName("A new account needs an email, and a free username")
    void newNeedsEmailAndFreeName() {
        assertThrows(ApiException.class, () -> service.add(ADMIN, ROOM,
            new EvaluationDto.TaRequest(null, "ghost", null), null));
        when(users.existsByUsername("taken")).thenReturn(true);
        assertThrows(ApiException.class, () -> service.add(ADMIN, ROOM,
            new EvaluationDto.TaRequest("someone@x.edu", "taken", null), null));
    }
}
