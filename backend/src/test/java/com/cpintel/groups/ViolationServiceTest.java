package com.cpintel.groups;

import com.cpintel.entity.ContestGroup;
import com.cpintel.entity.ContestViolation;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.ContestViolationRepository;
import com.cpintel.repository.jpa.GroupContestRepository;
import com.cpintel.repository.jpa.GroupMemberRepository;
import com.cpintel.repository.jpa.UserRepository;
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
 * What a client is allowed to report about itself.
 *
 * This is the one endpoint in the product where the data is evidence, so the rules that keep it
 * trustworthy are the ones worth testing: only the person it is about can file it, only for a
 * contest they are in, retries cannot inflate the count, and a client clock cannot place an
 * event outside the round it belongs to.
 */
class ViolationServiceTest {

    private static final Long USER = 7L;
    private static final Long CONTEST = 42L;
    private static final Long GROUP = 3L;

    private ContestViolationRepository violations;
    private GroupContestRepository contests;
    private GroupMemberRepository members;
    private UserRepository users;
    private ViolationService service;

    private Instant start;
    private Instant end;

    @BeforeEach
    void setUp() {
        violations = mock(ContestViolationRepository.class);
        contests = mock(GroupContestRepository.class);
        members = mock(GroupMemberRepository.class);
        users = mock(UserRepository.class);
        service = new ViolationService(violations, contests, members, users);

        start = Instant.now().minus(Duration.ofHours(1));
        end = start.plus(Duration.ofHours(2));

        GroupContest contest = GroupContest.builder()
            .contestId(CONTEST)
            .group(ContestGroup.builder().groupId(GROUP).name("Squad").build())
            .platform("CODEFORCES").externalId("2258").name("Round")
            .startsAt(start).endsAt(end)
            .build();

        when(contests.findById(CONTEST)).thenReturn(Optional.of(contest));
        when(members.existsByGroupGroupIdAndUserUserId(GROUP, USER)).thenReturn(true);
        when(users.findById(USER)).thenReturn(
            Optional.of(User.builder().userId(USER).username("member").build()));
        when(violations.existsByContestContestIdAndUserUserIdAndEventId(any(), any(), any()))
            .thenReturn(false);
    }

    private GroupsDto.ViolationEvent event(String id, String type, Instant at) {
        return new GroupsDto.ViolationEvent(id, type, "detail", 1_500L, at);
    }

    private int report(GroupsDto.ViolationEvent... events) {
        return service.report(USER, CONTEST, new GroupsDto.ViolationReport(List.of(events)));
    }

    @SuppressWarnings("unchecked")
    private List<ContestViolation> saved() {
        ArgumentCaptor<List<ContestViolation>> captor = ArgumentCaptor.forClass(List.class);
        verify(violations).saveAll(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("Who may report")
    class Authorisation {

        @Test
        @DisplayName("someone outside the group cannot report into its contest")
        void nonMemberRefused() {
            when(members.existsByGroupGroupIdAndUserUserId(GROUP, USER)).thenReturn(false);

            var e = assertThrows(ApiException.class,
                () -> report(event("a", "FOCUS_LOST", Instant.now())));

            assertTrue(e.getMessage().contains("not in the group"));
            verify(violations, never()).saveAll(any());
        }

        @Test
        @DisplayName("a contest that does not exist is a 404, not a silently dropped report")
        void unknownContest() {
            when(contests.findById(CONTEST)).thenReturn(Optional.empty());
            assertThrows(ApiException.class, () -> report(event("a", "FOCUS_LOST", Instant.now())));
        }
    }

    @Nested
    @DisplayName("Retries and duplicates")
    class Idempotency {

        @Test
        @DisplayName("an event already stored is not stored again")
        void duplicateIgnored() {
            when(violations.existsByContestContestIdAndUserUserIdAndEventId(CONTEST, USER, "a"))
                .thenReturn(true);

            // The desktop client retries a failed report. Without this, one focus loss on a
            // flaky connection becomes five and the count stops meaning anything.
            assertEquals(0, report(event("a", "FOCUS_LOST", Instant.now())));
            verify(violations, never()).saveAll(any());
        }

        @Test
        @DisplayName("a partly-seen batch stores only what is new")
        void partialBatch() {
            when(violations.existsByContestContestIdAndUserUserIdAndEventId(CONTEST, USER, "a"))
                .thenReturn(true);

            assertEquals(1, report(
                event("a", "FOCUS_LOST", Instant.now()),
                event("b", "FOCUS_LOST", Instant.now())));
            assertEquals("b", saved().get(0).getEventId());
        }

        @Test
        @DisplayName("two retries racing each other are not an error")
        void concurrentRetry() {
            doThrow(new org.springframework.dao.DataIntegrityViolationException("unique"))
                .when(violations).saveAll(any());

            // The unique index caught it. Nothing is lost, and the client must not be told its
            // report failed — it would only retry again.
            assertEquals(0, report(event("a", "FOCUS_LOST", Instant.now())));
        }
    }

    @Nested
    @DisplayName("Trusting the client's clock only so far")
    class Timestamps {

        @Test
        @DisplayName("an event inside the window is kept as sent")
        void insideWindow() {
            Instant during = start.plus(Duration.ofMinutes(20));
            report(event("a", "FOCUS_LOST", during));
            assertEquals(during, saved().get(0).getOccurredAt());
        }

        @Test
        @DisplayName("a little clock skew is pulled to the boundary rather than dropped")
        void slightSkewClamped() {
            report(event("a", "FOCUS_LOST", start.minus(Duration.ofMinutes(2))));
            assertEquals(start, saved().get(0).getOccurredAt());
        }

        @Test
        @DisplayName("an event dated well outside the contest is dropped")
        void wildTimestampDropped() {
            // A client that is badly wrong, or backdating deliberately, must not be able to
            // attach an event to a round it does not belong to.
            assertEquals(0, report(event("a", "FOCUS_LOST", start.minus(Duration.ofDays(2)))));
            verify(violations, never()).saveAll(any());
        }

        @Test
        @DisplayName("a report arriving just after the contest ends is still accepted")
        void graceAfterEnd() {
            // The last events of a round are reported as it closes; refusing them would lose
            // exactly the window most worth having.
            assertEquals(1, report(event("a", "FOCUS_LOST", end.plus(Duration.ofMinutes(2)))));
            assertEquals(end, saved().get(0).getOccurredAt());
        }
    }

    @Nested
    @DisplayName("What counts as an event")
    class Types {

        @Test
        @DisplayName("every type the lock can produce is accepted")
        void knownTypes() {
            for (ContestViolation.Type type : ContestViolation.Type.values()) {
                reset(violations);
                when(violations.existsByContestContestIdAndUserUserIdAndEventId(any(), any(), any()))
                    .thenReturn(false);
                assertEquals(1, report(event(type.name(), type.name(), Instant.now())),
                    type + " should be accepted");
            }
        }

        @Test
        @DisplayName("the type is normalised rather than rejected on case")
        void caseInsensitive() {
            assertEquals(1, report(event("a", "focus_lost", Instant.now())));
            assertEquals("FOCUS_LOST", saved().get(0).getType());
        }

        @Test
        @DisplayName("an unrecognised type is dropped, not stored")
        void unknownTypeDropped() {
            // An unreadable row in an evidence trail is worse than no row at all.
            assertEquals(0, report(event("a", "SOMETHING_NEW", Instant.now())));
            verify(violations, never()).saveAll(any());
        }

        @Test
        @DisplayName("an oversized batch is refused rather than absorbed")
        void batchLimit() {
            var events = new java.util.ArrayList<GroupsDto.ViolationEvent>();
            for (int i = 0; i < 500; i++) events.add(event("e" + i, "FOCUS_LOST", Instant.now()));

            assertThrows(ApiException.class,
                () -> service.report(USER, CONTEST, new GroupsDto.ViolationReport(events)));
        }

        @Test
        @DisplayName("an empty report is a no-op, not an error")
        void emptyReport() {
            assertEquals(0, service.report(USER, CONTEST, new GroupsDto.ViolationReport(List.of())));
        }
    }
}
