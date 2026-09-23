package com.cpintel.events;

import com.cpintel.entity.ContestGroup;
import com.cpintel.entity.GroupContest;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.*;
import com.cpintel.repository.mongo.CodeSubmissionRepository;
import com.cpintel.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The handful of rules that make an examination an examination.
 *
 * <p>Contests and examinations are one object with one service, so what separates them is not a
 * type but these rules — and a rule nothing tests is a rule that quietly stops applying. The
 * three that matter: an examination is never public, it runs on the judge this deployment
 * controls, and it is monitored unless an admin deliberately says otherwise.
 */
class EventServiceRulesTest {

    private static final Long ADMIN = 1L;
    private static final Long TEAM = 3L;
    private static final Long EVENT = 42L;

    private GroupContestRepository events;
    private ContestGroupRepository teams;
    private ContestAssignmentRepository assignments;
    private ContestProblemRepository problems;
    private ExamEventRepository examEventRepository;
    private EventService service;

    @BeforeEach
    void setUp() {
        events = mock(GroupContestRepository.class);
        teams = mock(ContestGroupRepository.class);
        assignments = mock(ContestAssignmentRepository.class);
        problems = mock(ContestProblemRepository.class);
        examEventRepository = mock(ExamEventRepository.class);

        service = new EventService(
            events, teams, assignments, problems,
            mock(GroupMemberRepository.class),
            mock(GroupStandingRepository.class),
            examEventRepository,
            mock(ExamPasscodeRepository.class),
            mock(CodeSubmissionRepository.class),
            mock(UserRepository.class),
            mock(ExamEventService.class),
            mock(ExamAccessService.class),
            mock(ExamPasswordService.class),
            mock(AuditService.class),
            new ObjectMapper());

        when(teams.findById(TEAM)).thenReturn(Optional.of(
            ContestGroup.builder().groupId(TEAM).name("Second years").isActive(true).build()));
        // A save also makes the row readable, because create() finishes by reading back the
        // detail it just wrote — which is exactly what the controller returns.
        when(events.save(any())).thenAnswer(call -> {
            GroupContest saved = call.getArgument(0);
            if (saved.getContestId() == null) saved.setContestId(EVENT);
            when(events.findById(EVENT)).thenReturn(Optional.of(saved));
            return saved;
        });
        when(assignments.findByContest(EVENT)).thenReturn(List.of());
        when(problems.findByContestContestIdOrderByOrderingAscLabelAsc(EVENT))
            .thenReturn(List.of());
        when(assignments.participantsByTeam(EVENT)).thenReturn(List.of());
        when(assignments.participantsNamedDirectly(EVENT)).thenReturn(List.of());
    }

    private EventsDto.EventRequest request(String kind, String platform, String visibility) {
        return new EventsDto.EventRequest(
            kind, platform, "midsem", "Mid-semester practical", null, null, null,
            Instant.now().plus(Duration.ofDays(1)),
            Instant.now().plus(Duration.ofDays(1)).plus(Duration.ofHours(2)),
            visibility, null, null, null, null, TEAM, null, null, null);
    }

    private GroupContest created() {
        ArgumentCaptor<GroupContest> captor = ArgumentCaptor.forClass(GroupContest.class);
        verify(events, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private void stubDetailRead(GroupContest event) {
        when(events.findById(EVENT)).thenReturn(Optional.of(event));
    }

    @Nested
    @DisplayName("What makes an examination an examination")
    class ExamRules {

        @Test
        @DisplayName("an examination is never public, whatever was asked for")
        void neverPublic() {
            // Applied rather than refused: a request that merely left the field at its default
            // should not fail, and an examination anybody could walk into is not one.
            service.create(ADMIN, request("EXAM", "DOMJUDGE", "PUBLIC"), null);

            assertEquals(GroupContest.Visibility.TEAMS.name(), created().getVisibility());
        }

        @Test
        @DisplayName("an examination runs on the judge this deployment controls")
        void domjudgeOnly() {
            // Codeforces cannot be asked to hold a contest open for one class at one time.
            ApiException thrown = assertThrows(ApiException.class,
                () -> service.create(ADMIN, request("EXAM", "CODEFORCES", "TEAMS"), null));

            assertTrue(thrown.getMessage().contains("DOMjudge"), thrown.getMessage());
        }

        @Test
        @DisplayName("an examination is monitored unless an admin says otherwise")
        void monitoredByDefault() {
            service.create(ADMIN, request("EXAM", "DOMJUDGE", null), null);

            assertTrue(created().getLockdownRequired());
        }

        @Test
        @DisplayName("a contest is not monitored unless somebody asks for it")
        void contestNotMonitoredByDefault() {
            service.create(ADMIN, request("CONTEST", "DOMJUDGE", "PUBLIC"), null);

            GroupContest contest = created();
            assertFalse(contest.getLockdownRequired());
            assertEquals(GroupContest.Visibility.PUBLIC.name(), contest.getVisibility());
        }

        @Test
        @DisplayName("everything is created as a draft")
        void createdAsDraft() {
            // Saving a half-written examination must never be the same click as publishing it
            // to two hundred candidates.
            service.create(ADMIN, request("EXAM", "DOMJUDGE", null), null);

            assertEquals(GroupContest.Lifecycle.DRAFT.name(), created().getLifecycle());
        }

        @Test
        @DisplayName("the owning team is assigned, or nobody could enter")
        void ownerIsAssigned() {
            service.create(ADMIN, request("EXAM", "DOMJUDGE", null), null);

            verify(assignments).save(argThat(assignment ->
                assignment.getGroup() != null
                    && TEAM.equals(assignment.getGroup().getGroupId())));
        }

        @Test
        @DisplayName("an away threshold outside its bounds is refused")
        void thresholdIsBounded() {
            EventsDto.EventRequest req = new EventsDto.EventRequest(
                "EXAM", "DOMJUDGE", "midsem", "Paper", null, null, null,
                null, null, null, null, 0, null, null, TEAM, null, null, null);

            assertThrows(ApiException.class, () -> service.create(ADMIN, req, null));
        }

        @Test
        @DisplayName("an event that ends before it starts is refused")
        void windowMustBeOrdered() {
            Instant start = Instant.now().plus(Duration.ofDays(1));
            EventsDto.EventRequest req = new EventsDto.EventRequest(
                "EXAM", "DOMJUDGE", "midsem", "Paper", null, null, null,
                start, start.minus(Duration.ofHours(1)), null, null, null, null, null,
                TEAM, null, null, null);

            assertThrows(ApiException.class, () -> service.create(ADMIN, req, null));
        }
    }

    @Nested
    @DisplayName("Moving an event along its life")
    class Lifecycle {

        private GroupContest event(String lifecycle, Instant startsAt, Instant endsAt) {
            GroupContest event = GroupContest.builder()
                .contestId(EVENT)
                .kind(GroupContest.Kind.EXAM.name())
                .lifecycle(lifecycle)
                .platform("DOMJUDGE")
                .externalId("midsem")
                .name("Paper")
                .startsAt(startsAt)
                .endsAt(endsAt)
                .build();
            stubDetailRead(event);
            return event;
        }

        @Test
        @DisplayName("publishing needs a window, because the clock is what opens it")
        void publishNeedsWindow() {
            event("DRAFT", null, null);

            assertThrows(ApiException.class,
                () -> service.setLifecycle(ADMIN, EVENT, "SCHEDULED", null));
        }

        @Test
        @DisplayName("ending early moves the window rather than setting a flag")
        void endingEarlyMovesTheWindow() {
            // The window is what the arena reads. Leaving it in the future and marking the row
            // ended would have this screen and the submission gate disagreeing.
            GroupContest exam = event("SCHEDULED",
                Instant.now().minus(Duration.ofMinutes(10)),
                Instant.now().plus(Duration.ofHours(2)));

            service.setLifecycle(ADMIN, EVENT, "ENDED", null);

            assertFalse(exam.getEndsAt().isAfter(Instant.now().plusSeconds(1)));
            assertFalse(exam.isOpenForParticipation(Instant.now()));
        }

        @Test
        @DisplayName("a running examination cannot be taken back to a draft")
        void cannotDraftALivePaper() {
            event("SCHEDULED",
                Instant.now().minus(Duration.ofMinutes(10)),
                Instant.now().plus(Duration.ofHours(2)));

            assertThrows(ApiException.class,
                () -> service.setLifecycle(ADMIN, EVENT, "DRAFT", null));
        }

        @Test
        @DisplayName("a running examination cannot be archived")
        void cannotArchiveALivePaper() {
            event("SCHEDULED",
                Instant.now().minus(Duration.ofMinutes(10)),
                Instant.now().plus(Duration.ofHours(2)));

            assertThrows(ApiException.class,
                () -> service.setLifecycle(ADMIN, EVENT, "ARCHIVED", null));
        }

        @Test
        @DisplayName("ACTIVE cannot be set by hand — the window decides that")
        void activeIsNotSettable() {
            event("SCHEDULED",
                Instant.now().plus(Duration.ofHours(1)),
                Instant.now().plus(Duration.ofHours(2)));

            assertThrows(ApiException.class,
                () -> service.setLifecycle(ADMIN, EVENT, "ACTIVE", null));
        }
    }

    @Nested
    @DisplayName("Deleting")
    class Deleting {

        @Test
        @DisplayName("an event people have already sat cannot be deleted")
        void refusesWhenSat() {
            // The log is the record of something that happened to people. Archiving is how a
            // finished examination goes away.
            GroupContest exam = GroupContest.builder()
                .contestId(EVENT)
                .kind(GroupContest.Kind.EXAM.name())
                .lifecycle("SCHEDULED")
                .platform("DOMJUDGE")
                .externalId("midsem")
                .name("Paper")
                .startsAt(Instant.now().minus(Duration.ofHours(3)))
                .endsAt(Instant.now().minus(Duration.ofHours(1)))
                .build();
            stubDetailRead(exam);
            when(examEventRepository.countByContestContestId(EVENT)).thenReturn(400L);

            assertThrows(ApiException.class, () -> service.delete(ADMIN, EVENT, null));
            verify(events, never()).delete(any());
        }

        @Test
        @DisplayName("a draft nobody has sat can be deleted")
        void allowsWhenUnused() {
            GroupContest draft = GroupContest.builder()
                .contestId(EVENT)
                .kind(GroupContest.Kind.EXAM.name())
                .lifecycle("DRAFT")
                .platform("DOMJUDGE")
                .externalId("midsem")
                .name("Paper")
                .build();
            stubDetailRead(draft);
            when(examEventRepository.countByContestContestId(EVENT)).thenReturn(0L);

            service.delete(ADMIN, EVENT, null);

            verify(events).delete(draft);
        }
    }
}
