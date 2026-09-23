package com.cpintel.groups;

import com.cpintel.entity.GroupContest;
import com.cpintel.events.EventService;
import com.cpintel.exception.ApiException;
import com.cpintel.practice.PracticeDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Narrowing an event's languages to what its administrator allowed.
 *
 * <p>The interesting half is the unrestricted one. Practice, every contest and most
 * examinations set nothing, and all of them have to pass through untouched — a policy that
 * narrows by accident takes a language away from somebody mid-round, which is worse than the
 * restriction not existing.
 */
class LanguagePolicyTest {

    private static final Long USER = 42L;
    private static final Long CONTEST = 9L;
    private static final String CID = "nwerc18";

    private GroupService groups;
    private EventService events;
    private LanguagePolicy policy;

    private GroupContest event;

    @BeforeEach
    void setUp() {
        groups = mock(GroupService.class);
        events = mock(EventService.class);
        policy = new LanguagePolicy(groups, events);

        event = GroupContest.builder().contestId(CONTEST).kind("EXAM").build();
        when(groups.activeFor(USER, "DOMJUDGE", CID)).thenReturn(summary());
        when(events.require(CONTEST)).thenReturn(event);
    }

    private GroupsDto.ContestSummary summary() {
        return new GroupsDto.ContestSummary(
            CONTEST, "EXAM", 1L, "Class", "DOMJUDGE", CID, "Paper", null,
            Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600),
            true, 10, "LIVE", "ACTIVE", null, null);
    }

    /** What a DOMjudge contest typically offers. */
    private List<PracticeDto.LanguageOption> judgeOffers() {
        return List.of(
            new PracticeDto.LanguageOption("cpp", "C++"),
            new PracticeDto.LanguageOption("python3", "Python 3"),
            new PracticeDto.LanguageOption("java", "Java"),
            new PracticeDto.LanguageOption("c", "C"));
    }

    private List<String> ids(List<PracticeDto.LanguageOption> options) {
        return options.stream().map(PracticeDto.LanguageOption::id).toList();
    }

    @Nested
    @DisplayName("Events that restrict nothing")
    class Unrestricted {

        @Test
        @DisplayName("an event with no list set restricts nothing")
        void nothingSet() {
            assertTrue(policy.restrictionFor(USER, "DOMJUDGE", CID).isEmpty());
            assertEquals(judgeOffers(), policy.filter(judgeOffers(), Set.of()));
        }

        @Test
        @DisplayName("a contest nobody is sitting through CPIntel restricts nothing")
        void noEventAtAll() {
            when(groups.activeFor(USER, "DOMJUDGE", "practice")).thenReturn(null);

            assertTrue(policy.restrictionFor(USER, "DOMJUDGE", "practice").isEmpty());
            verifyNoInteractions(events);
        }

        /**
         * A malformed event reference must not refuse to run code.
         *
         * The local runner takes these two values as an untrusted hint from the page. Naming an
         * event is only ever a way to be held to more rules, so a hint that names nothing has
         * to degrade to "no event" — omitting it entirely already means the same thing.
         */
        @Test
        @DisplayName("an unrecognised platform restricts nothing rather than erroring")
        void badPlatformDegrades() {
            when(groups.activeFor(USER, "NOT-A-JUDGE", CID))
                .thenThrow(ApiException.badRequest("Platform must be CODEFORCES or DOMJUDGE"));

            assertTrue(policy.restrictionFor(USER, "NOT-A-JUDGE", CID).isEmpty());
        }

        /**
         * A stored list that names nothing recognisable reads as no restriction.
         *
         * The other reading — "nothing is allowed" — locks a whole room out of a paper over a
         * data problem none of them can see or fix.
         */
        @Test
        @DisplayName("a stored list of nonsense is treated as unrestricted, not as a total ban")
        void unusableListIsUnrestricted() {
            event.setAllowedLanguages("brainfuck,whitespace");

            assertTrue(policy.restrictionFor(USER, "DOMJUDGE", CID).isEmpty());
        }

        @Test
        @DisplayName("an empty filter set is the identity")
        void emptySetFiltersNothing() {
            assertEquals(4, policy.filter(judgeOffers(), Set.of()).size());
        }
    }

    @Nested
    @DisplayName("Events that restrict")
    class Restricted {

        @BeforeEach
        void restrictToCppAndPython() {
            event.setAllowedLanguages("cpp,python3");
        }

        @Test
        @DisplayName("the restriction is read back from the row")
        void readsTheList() {
            assertEquals(Set.of("cpp", "python3"),
                policy.restrictionFor(USER, "DOMJUDGE", CID));
        }

        @Test
        @DisplayName("the picker is narrowed to what was allowed")
        void filtersThePicker() {
            Set<String> allowed = policy.restrictionFor(USER, "DOMJUDGE", CID);

            assertEquals(List.of("cpp", "python3"), ids(policy.filter(judgeOffers(), allowed)));
        }

        @Test
        @DisplayName("the judge's order is kept, because it is the order they already know")
        void keepsJudgeOrder() {
            List<PracticeDto.LanguageOption> offered = List.of(
                new PracticeDto.LanguageOption("python3", "Python 3"),
                new PracticeDto.LanguageOption("cpp", "C++"));

            assertEquals(List.of("python3", "cpp"),
                ids(policy.filter(offered, Set.of("cpp", "python3"))));
        }

        /**
         * Fails closed, which is the direction that respects what the admin said.
         *
         * An admin who listed two languages meant two; offering a third because the judge calls
         * it something this system has not seen would quietly overrule them.
         */
        @Test
        @DisplayName("a language nothing can classify is hidden while a restriction is on")
        void unclassifiableIsHidden() {
            List<PracticeDto.LanguageOption> offered = List.of(
                new PracticeDto.LanguageOption("cpp", "C++"),
                new PracticeDto.LanguageOption("bf", "Brainfuck"));

            assertEquals(List.of("cpp"), ids(policy.filter(offered, Set.of("cpp", "python3"))));
        }

        @Test
        @DisplayName("a judge that offers none of the allowed languages yields an empty picker")
        void noOverlap() {
            List<PracticeDto.LanguageOption> offered =
                List.of(new PracticeDto.LanguageOption("java", "Java 21"));

            assertTrue(policy.filter(offered, Set.of("cpp", "python3")).isEmpty());
        }

        @Test
        @DisplayName("an entry that is not a known language is dropped from the restriction")
        void unknownEntriesDropped() {
            event.setAllowedLanguages("cpp, fortran ,PYTHON3");

            // Case and spacing folded; the unknown entry ignored rather than treated as a
            // language nothing will ever match.
            assertEquals(Set.of("cpp", "python3"),
                policy.restrictionFor(USER, "DOMJUDGE", CID));
        }
    }

    @Nested
    @DisplayName("Refusing a submission")
    class Gate {

        @Test
        @DisplayName("an unrestricted event accepts anything")
        void unrestrictedAcceptsAnything() {
            assertDoesNotThrow(() ->
                policy.requireAllowed(Set.of(), "java", List.of()));
        }

        @Test
        @DisplayName("a language that was offered is accepted")
        void offeredIsAccepted() {
            Set<String> allowed = Set.of("cpp", "python3");

            assertDoesNotThrow(() -> policy.requireAllowed(
                allowed, "python3", policy.filter(judgeOffers(), allowed)));
        }

        @Test
        @DisplayName("a language that was not offered is refused, and the message names what is")
        void notOfferedIsRefused() {
            Set<String> allowed = Set.of("cpp", "python3");

            ApiException e = assertThrows(ApiException.class, () -> policy.requireAllowed(
                allowed, "java", policy.filter(judgeOffers(), allowed)));

            // Named in the admin's terms, not the judge's ids: the candidate has to act on it.
            assertTrue(e.getMessage().contains("C++"), e.getMessage());
            assertTrue(e.getMessage().contains("Python 3"), e.getMessage());
        }

        /**
         * The gate reads the same filtered list the picker was built from.
         *
         * That is what makes "you were offered it" and "you may send it" the same statement
         * rather than two rules that could drift apart.
         */
        @Test
        @DisplayName("the gate and the picker cannot disagree")
        void gateMatchesPicker() {
            Set<String> allowed = Set.of("cpp", "python3");
            List<PracticeDto.LanguageOption> shown = policy.filter(judgeOffers(), allowed);

            for (PracticeDto.LanguageOption option : shown) {
                assertDoesNotThrow(() -> policy.requireAllowed(allowed, option.id(), shown),
                    option.id() + " was offered, so it must be accepted");
            }
            for (PracticeDto.LanguageOption option : judgeOffers()) {
                if (shown.stream().anyMatch(s -> s.id().equals(option.id()))) continue;
                assertThrows(ApiException.class,
                    () -> policy.requireAllowed(allowed, option.id(), shown),
                    option.id() + " was hidden, so it must be refused");
            }
        }
    }
}
