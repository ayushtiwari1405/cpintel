package com.cpintel.events;

import com.cpintel.entity.GroupContest;
import com.cpintel.exception.ApiException;
import com.cpintel.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Whether a candidate may be inside a paper right now.
 *
 * <p>Two failure directions, and they are not symmetrical. Letting somebody in who should not be
 * defeats the point of invigilating; keeping somebody out who should be in costs them exam time
 * they do not get back. So the tests below are as interested in the paper that asks for nothing
 * and the grant that survives to the last minute as they are in the refusals.
 */
class ExamAccessServiceTest {

    private static final Long EXAM = 7L;
    private static final Long USER = 42L;

    private ExamPasswordService passwords;
    private ExamAccessService service;
    private final Map<String, String> redisStore = new HashMap<>();

    private GroupContest exam;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(c -> redisStore.get(c.<String>getArgument(0)));
        doAnswer(c -> redisStore.put(c.getArgument(0), c.getArgument(1)))
            .when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenAnswer(c ->
            redisStore.remove(c.<String>getArgument(0)) != null);

        passwords = mock(ExamPasswordService.class);
        service = new ExamAccessService(redis, passwords,
            mock(ExamEventService.class), mock(AuditService.class));

        redisStore.clear();
        exam = GroupContest.builder()
            .contestId(EXAM).kind("EXAM")
            .lifecycle("SCHEDULED")
            .startsAt(Instant.now().minusSeconds(60))
            .endsAt(Instant.now().plusSeconds(3600))
            .examPasswordGen(1)
            .build();
    }

    private void paperAsksForAPassword() {
        when(passwords.requiresExamPassword(exam)).thenReturn(true);
    }

    @Nested
    @DisplayName("Papers that ask for nothing")
    class NoPassword {

        /**
         * An admin who generated no password has not decided to require one.
         *
         * Inventing the requirement would shut a room out of a paper that was about to start,
         * over a setting nobody touched.
         */
        @Test
        @DisplayName("an examination with no passwords is always unlocked")
        void alwaysOpen() {
            assertFalse(service.requiresUnlock(exam, USER));
            assertTrue(service.isUnlocked(exam, USER));
            assertDoesNotThrow(() -> service.requireUnlocked(exam, USER));
        }

        @Test
        @DisplayName("a contest never asks, whatever is set on the row")
        void contestsNeverAsk() {
            GroupContest contest = GroupContest.builder()
                .contestId(EXAM).kind("CONTEST").examPasswordGen(1).build();
            when(passwords.requiresExamPassword(contest)).thenReturn(true);

            assertFalse(service.requiresUnlock(contest, USER));
            assertTrue(service.isUnlocked(contest, USER));
        }
    }

    @Nested
    @DisplayName("Unlocking")
    class Unlocking {

        @Test
        @DisplayName("the right password lets them in for the rest of the sitting")
        void correctPasswordGrants() {
            paperAsksForAPassword();
            when(passwords.verify(exam, USER, "K7FQ-M2XB", null)).thenReturn(true);

            assertFalse(service.isUnlocked(exam, USER));
            service.unlock(exam, USER, "K7FQ-M2XB", null, null);

            assertTrue(service.isUnlocked(exam, USER));
            assertDoesNotThrow(() -> service.requireUnlocked(exam, USER));
        }

        @Test
        @DisplayName("a wrong password is refused without saying which half was wrong")
        void wrongPasswordRefused() {
            paperAsksForAPassword();
            when(passwords.verify(any(), any(), any(), any())).thenReturn(false);

            ApiException e = assertThrows(ApiException.class,
                () -> service.unlock(exam, USER, "NOPE", "NOPE", null));

            String message = e.getMessage().toLowerCase();
            assertFalse(service.isUnlocked(exam, USER));
            // Telling somebody who has one of the two which one they are missing turns the
            // pair into two independent guesses.
            assertFalse(message.contains("examination password was")
                || message.contains("your code was"),
                "the refusal must not name which half failed");
        }

        @Test
        @DisplayName("a paper that has not started cannot be unlocked early")
        void notYetOpen() {
            paperAsksForAPassword();
            exam.setStartsAt(Instant.now().plusSeconds(600));
            when(passwords.verify(any(), any(), any(), any())).thenReturn(true);

            ApiException e = assertThrows(ApiException.class,
                () -> service.unlock(exam, USER, "right", null, null));

            // Somebody who got hold of the password early must not be able to read the paper
            // before the room does.
            assertTrue(e.getMessage().toLowerCase().contains("not open"));
            verify(passwords, never()).verify(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a paper that has ended cannot be unlocked either")
        void alreadyOver() {
            paperAsksForAPassword();
            exam.setStartsAt(Instant.now().minusSeconds(7200));
            exam.setEndsAt(Instant.now().minusSeconds(60));

            assertThrows(ApiException.class,
                () -> service.unlock(exam, USER, "right", null, null));
        }

        @Test
        @DisplayName("a draft is shut however its clock reads")
        void draftsStayShut() {
            paperAsksForAPassword();
            exam.setLifecycle("DRAFT");

            assertThrows(ApiException.class,
                () -> service.unlock(exam, USER, "right", null, null));
        }
    }

    @Nested
    @DisplayName("Rotation and revocation")
    class Ending {

        /**
         * The reason rotation exists at all.
         *
         * A password read out to the wrong room is only fixed by closing the sessions it
         * already opened. Rotation that affected only people who had not got in yet would be
         * the opposite of who it is for.
         */
        @Test
        @DisplayName("rotating the password ends the sessions it opened")
        void rotationEndsGrants() {
            paperAsksForAPassword();
            when(passwords.verify(any(), any(), any(), any())).thenReturn(true);
            service.unlock(exam, USER, "first", null, null);
            assertTrue(service.isUnlocked(exam, USER));

            exam.setExamPasswordGen(2);

            assertFalse(service.isUnlocked(exam, USER));
        }

        @Test
        @DisplayName("revoking one candidate leaves everybody else inside")
        void revokeIsPerCandidate() {
            paperAsksForAPassword();
            when(passwords.verify(any(), any(), any(), any())).thenReturn(true);
            service.unlock(exam, USER, "right", null, null);
            service.unlock(exam, 99L, "right", null, null);

            service.revoke(EXAM, USER);

            assertFalse(service.isUnlocked(exam, USER));
            assertTrue(service.isUnlocked(exam, 99L));
        }

        @Test
        @DisplayName("a grant from a different examination does not open this one")
        void grantsArePerExamination() {
            paperAsksForAPassword();
            when(passwords.verify(any(), any(), any(), any())).thenReturn(true);
            service.unlock(exam, USER, "right", null, null);

            GroupContest other = GroupContest.builder()
                .contestId(8L).kind("EXAM").examPasswordGen(1).build();
            when(passwords.requiresExamPassword(other)).thenReturn(true);

            assertFalse(service.isUnlocked(other, USER));
        }
    }
}
