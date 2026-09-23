package com.cpintel.events;

import com.cpintel.entity.ExamPasscode;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.ExamPasscodeRepository;
import com.cpintel.repository.jpa.GroupContestRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The passwords that open an examination.
 *
 * <p>Two properties are worth more than the rest and are tested first. A wrong password must
 * never open a paper — including the empty one, which is what an unreadable stored blob decrypts
 * to, and which without a guard would turn a key rotation into "every examination in the
 * deployment now accepts nothing". And a right password typed by a real person at a desk must
 * open it, hyphens or no hyphens, capitals or no capitals, because the alternative is a room
 * full of hands going up over punctuation.
 */
class ExamPasswordServiceTest {

    private static final Long EXAM = 7L;
    private static final Long ADMIN = 1L;
    private static final Long CANDIDATE = 42L;

    private GroupContestRepository events;
    private ExamPasscodeRepository passcodes;
    private UserRepository users;
    private ExamPasswordService service;

    private GroupContest exam;
    private final Map<Long, ExamPasscode> stored = new HashMap<>();

    @BeforeEach
    void setUp() {
        events = mock(GroupContestRepository.class);
        passcodes = mock(ExamPasscodeRepository.class);
        users = mock(UserRepository.class);
        service = new ExamPasswordService(events, passcodes, users, mock(AuditService.class));

        ReflectionTestUtils.setField(service, "passwordKey", "a-test-key-that-is-long-enough");
        ReflectionTestUtils.setField(service, "passwordLength", 8);

        exam = GroupContest.builder().contestId(EXAM).kind("EXAM").examPasswordGen(0).build();
        when(events.findById(EXAM)).thenReturn(Optional.of(exam));
        when(events.save(any())).thenAnswer(call -> call.getArgument(0));
        when(users.getReferenceById(ADMIN)).thenReturn(User.builder().userId(ADMIN).build());
        when(users.findById(CANDIDATE)).thenReturn(Optional.of(
            User.builder().userId(CANDIDATE).username("ada").fullName("Ada L").build()));

        stored.clear();
        when(passcodes.save(any())).thenAnswer(call -> {
            ExamPasscode row = call.getArgument(0);
            stored.put(row.getUser().getUserId(), row);
            return row;
        });
        when(passcodes.find(eq(EXAM), any())).thenAnswer(call ->
            Optional.ofNullable(stored.get(call.<Long>getArgument(1))));
        when(passcodes.findAllForContest(EXAM)).thenAnswer(call ->
            java.util.List.copyOf(stored.values()));
    }

    @Nested
    @DisplayName("Verifying what a candidate typed")
    class Verifying {

        @Test
        @DisplayName("the right examination password opens the paper")
        void correctPasswordPasses() {
            String password = service.generateExamPassword(ADMIN, EXAM, null);

            assertTrue(service.verify(exam, CANDIDATE, password, null));
        }

        @Test
        @DisplayName("a wrong examination password does not")
        void wrongPasswordFails() {
            service.generateExamPassword(ADMIN, EXAM, null);

            assertFalse(service.verify(exam, CANDIDATE, "NOPE-NOPE", null));
        }

        /**
         * The one that would be a deployment-wide hole.
         *
         * An unreadable stored blob — which is what every password becomes the moment somebody
         * rotates CPINTEL_EXAM_PASSWORD_KEY — decrypts to the empty string. Without the guard
         * in constantTimeEquals, an empty expectation matches an empty submission, and every
         * examination in the deployment silently starts accepting a blank password.
         */
        @Test
        @DisplayName("a password the server can no longer read opens nothing")
        void unreadablePasswordOpensNothing() {
            service.generateExamPassword(ADMIN, EXAM, null);
            // What a key rotation does to every stored blob at once.
            ReflectionTestUtils.setField(service, "passwordKey", "an-entirely-different-key");

            assertFalse(service.verify(exam, CANDIDATE, "", null));
            assertFalse(service.verify(exam, CANDIDATE, null, null));
            assertFalse(service.verify(exam, CANDIDATE, "anything", null));
        }

        @Test
        @DisplayName("punctuation and case are not part of the answer")
        void typedLoosely() {
            String password = service.generateExamPassword(ADMIN, EXAM, null);

            assertTrue(service.verify(exam, CANDIDATE, password.replace("-", ""), null),
                "somebody who does not type the hyphens has still typed the password");
            assertTrue(service.verify(exam, CANDIDATE, password.toLowerCase(), null));
            assertTrue(service.verify(exam, CANDIDATE, "  " + password + " ", null));
            assertTrue(service.verify(exam, CANDIDATE, password.replace("-", " "), null));
        }

        @Test
        @DisplayName("a paper with no passwords at all asks for nothing")
        void noPasswordsMeansOpen() {
            assertFalse(service.requiresExamPassword(exam));
            assertTrue(service.verify(exam, CANDIDATE, null, null));
        }

        @Test
        @DisplayName("both the shared password and the personal code have to be right")
        void bothHalvesRequired() {
            String shared = service.generateExamPassword(ADMIN, EXAM, null);
            String personal = service.issuePasscodes(
                ADMIN, EXAM, Set.of(CANDIDATE), false, null).get(0).code();

            assertTrue(service.verify(exam, CANDIDATE, shared, personal));
            assertFalse(service.verify(exam, CANDIDATE, shared, "WRNG-WRNG"),
                "the room's password alone is not enough — everybody in the room has it");
            assertFalse(service.verify(exam, CANDIDATE, "WRNG-WRNG", personal));
        }

        @Test
        @DisplayName("one candidate's code does not open the paper for another")
        void codesAreNotInterchangeable() {
            when(users.findById(99L)).thenReturn(Optional.of(
                User.builder().userId(99L).username("bob").build()));
            var issued = service.issuePasscodes(ADMIN, EXAM, Set.of(CANDIDATE, 99L), false, null);

            String adasCode = issued.stream()
                .filter(row -> row.userId().equals(CANDIDATE)).findFirst().orElseThrow().code();

            assertTrue(service.verify(exam, CANDIDATE, null, adasCode));
            assertFalse(service.verify(exam, 99L, null, adasCode));
        }
    }

    @Nested
    @DisplayName("Issuing and rotating")
    class Issuing {

        @Test
        @DisplayName("rotating the examination password moves its generation")
        void rotationBumpsGeneration() {
            service.generateExamPassword(ADMIN, EXAM, null);
            assertEquals(1, exam.getExamPasswordGen());

            String second = service.generateExamPassword(ADMIN, EXAM, null);

            // Two: which is what invalidates every access grant minted under the first.
            assertEquals(2, exam.getExamPasswordGen());
            assertTrue(service.verify(exam, CANDIDATE, second, null));
        }

        @Test
        @DisplayName("issuing again leaves the slips already printed alone")
        void issuingIsAdditive() {
            String first = service.issuePasscodes(
                ADMIN, EXAM, Set.of(CANDIDATE), false, null).get(0).code();

            String again = service.issuePasscodes(
                ADMIN, EXAM, Set.of(CANDIDATE), false, null).get(0).code();

            assertEquals(first, again,
                "a candidate added this morning must not invalidate two hundred printed slips");
        }

        @Test
        @DisplayName("regenerating replaces every code")
        void regenerateReplaces() {
            String first = service.issuePasscodes(
                ADMIN, EXAM, Set.of(CANDIDATE), false, null).get(0).code();

            String rotated = service.issuePasscodes(
                ADMIN, EXAM, Set.of(CANDIDATE), true, null).get(0).code();

            assertNotEquals(first, rotated);
            assertFalse(service.verify(exam, CANDIDATE, null, first));
            assertTrue(service.verify(exam, CANDIDATE, null, rotated));
        }

        @Test
        @DisplayName("a code is read back exactly as it was issued")
        void revealMatches() {
            String issued = service.issuePasscodes(
                ADMIN, EXAM, Set.of(CANDIDATE), false, null).get(0).code();

            assertEquals(issued, service.revealPasscodes(ADMIN, EXAM, null).get(0).code());
        }

        @Test
        @DisplayName("generated codes avoid the characters nobody can read off paper")
        void alphabetIsUnambiguous() {
            for (int i = 0; i < 50; i++) {
                String code = service.generateExamPassword(ADMIN, EXAM, null);
                assertFalse(code.matches(".*[O0I1l].*"),
                    "a code read off a slip under pressure cannot contain O, 0, I, 1 or l: "
                        + code);
            }
        }

        @Test
        @DisplayName("a contest has no passwords, and asking is an error rather than a silence")
        void contestsRefused() {
            GroupContest contest = GroupContest.builder().contestId(8L).kind("CONTEST").build();
            when(events.findById(8L)).thenReturn(Optional.of(contest));

            ApiException e = assertThrows(ApiException.class,
                () -> service.generateExamPassword(ADMIN, 8L, null));
            assertTrue(e.getMessage().toLowerCase().contains("examination"));
        }

        @Test
        @DisplayName("nothing can be generated without a key, and the message says which one")
        void keyRequired() {
            ReflectionTestUtils.setField(service, "passwordKey", "");

            assertFalse(service.isConfigured());
            ApiException e = assertThrows(ApiException.class,
                () -> service.generateExamPassword(ADMIN, EXAM, null));
            assertTrue(e.getMessage().contains("CPINTEL_EXAM_PASSWORD_KEY"));
        }

        @Test
        @DisplayName("an examination nobody is assigned to says so rather than issuing nothing")
        void emptyRosterIsExplained() {
            ApiException e = assertThrows(ApiException.class,
                () -> service.issuePasscodes(ADMIN, EXAM, Set.of(), false, null));
            assertTrue(e.getMessage().toLowerCase().contains("assign"));
        }
    }
}
