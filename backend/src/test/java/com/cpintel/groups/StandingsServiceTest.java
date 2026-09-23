package com.cpintel.groups;

import com.cpintel.entity.ContestGroup;
import com.cpintel.entity.GroupContest;
import com.cpintel.integration.domjudge.DomjudgeCredentialStore;
import com.cpintel.entity.GroupMember;
import com.cpintel.entity.GroupStanding;
import com.cpintel.entity.PlatformAccount;
import com.cpintel.entity.User;
import com.cpintel.repository.jpa.GroupMemberRepository;
import com.cpintel.repository.jpa.GroupStandingRepository;
import com.cpintel.repository.jpa.PlatformAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * How a group is ordered, and who is left out of the ordering.
 *
 * The ranking is the number this whole feature exists to produce, and two of its rules are
 * easy to get quietly wrong: ties must share a rank rather than being separated by an
 * arbitrary tie-break, and a member the judge has never heard of must not be ranked last as
 * though they had sat the contest and failed.
 */
class StandingsServiceTest {

    private PlatformAccountRepository accounts;
    private StandingsService service;
    private GroupContest contest;
    private Map<Long, User> users;

    @BeforeEach
    void setUp() {
        accounts = mock(PlatformAccountRepository.class);
        service = new StandingsService(
            mock(GroupMemberRepository.class),
            mock(GroupStandingRepository.class),
            accounts,
            List.of(),
            mock(DomjudgeCredentialStore.class));

        ContestGroup group = ContestGroup.builder().groupId(1L).name("Squad").build();
        contest = GroupContest.builder()
            .contestId(10L).group(group)
            .platform("CODEFORCES").externalId("2258").name("Round")
            .build();

        users = new HashMap<>();
        for (long id = 1; id <= 5; id++) {
            users.put(id, User.builder().userId(id).username("user" + id).build());
        }
    }

    private StandingsProvider.Result result(long userId, boolean found, int solved, int penalty) {
        return new StandingsProvider.Result(
            userId, "h" + userId, found, solved, penalty, (double) solved, null);
    }

    private Map<Long, GroupStanding> byUser(List<GroupStanding> rows) {
        Map<Long, GroupStanding> map = new HashMap<>();
        for (GroupStanding row : rows) map.put(row.getUser().getUserId(), row);
        return map;
    }

    @Nested
    @DisplayName("Ordering")
    class Ordering {

        @Test
        @DisplayName("more problems beats fewer, whatever the penalty")
        void solvedFirst() {
            var rows = byUser(service.rank(List.of(
                result(1, true, 2, 500),
                result(2, true, 3, 900)), users, contest));

            assertEquals(1, rows.get(2L).getGroupRank());
            assertEquals(2, rows.get(1L).getGroupRank());
        }

        @Test
        @DisplayName("equal problems are separated by penalty, lower first")
        void penaltyBreaksSolved() {
            var rows = byUser(service.rank(List.of(
                result(1, true, 3, 400),
                result(2, true, 3, 120)), users, contest));

            assertEquals(1, rows.get(2L).getGroupRank());
            assertEquals(2, rows.get(1L).getGroupRank());
        }

        @Test
        @DisplayName("identical results share a rank, and the next one skips the numbers used")
        void tiesShareARank() {
            // Standard competition ranking: 1, 2, 2, 4. Telling two people with identical
            // results that one of them is ahead would invent a distinction the contest did not
            // make — and whoever came fourth really did come fourth.
            var rows = byUser(service.rank(List.of(
                result(1, true, 4, 100),
                result(2, true, 3, 200),
                result(3, true, 3, 200),
                result(4, true, 1, 50)), users, contest));

            assertEquals(1, rows.get(1L).getGroupRank());
            assertEquals(2, rows.get(2L).getGroupRank());
            assertEquals(2, rows.get(3L).getGroupRank());
            assertEquals(4, rows.get(4L).getGroupRank());
        }

        @Test
        @DisplayName("the same inputs always produce the same board")
        void orderingIsStable() {
            // Without a final tie-break, two members with identical results could swap places
            // between refreshes and look like they had overtaken each other.
            List<StandingsProvider.Result> input = List.of(
                result(3, true, 2, 100),
                result(1, true, 2, 100),
                result(2, true, 2, 100));

            var first = byUser(service.rank(input, users, contest));
            var second = byUser(service.rank(input, users, contest));

            for (long id = 1; id <= 3; id++) {
                assertEquals(first.get(id).getGroupRank(), second.get(id).getGroupRank());
            }
        }
    }

    @Nested
    @DisplayName("Members the judge does not know")
    class Unmatched {

        @Test
        @DisplayName("an unfound member is left unranked rather than ranked last")
        void unfoundIsUnranked() {
            var rows = byUser(service.rank(List.of(
                result(1, true, 2, 100),
                result(2, false, 0, 0)), users, contest));

            assertEquals(1, rows.get(1L).getGroupRank());
            assertNull(rows.get(2L).getGroupRank(),
                "a handle that does not match is a configuration problem, not a last place");
            assertFalse(rows.get(2L).getFound());
        }

        @Test
        @DisplayName("someone found with nothing solved is still ranked")
        void foundWithZeroIsRanked() {
            // The difference that matters: this person sat the contest and solved nothing,
            // which is a real result. The row above could not be read at all.
            var rows = byUser(service.rank(List.of(
                result(1, true, 2, 100),
                result(2, true, 0, 0)), users, contest));

            assertEquals(2, rows.get(2L).getGroupRank());
            assertTrue(rows.get(2L).getFound());
        }

        @Test
        @DisplayName("unfound members do not consume rank numbers")
        void unfoundDoesNotShiftRanks() {
            var rows = byUser(service.rank(List.of(
                result(1, false, 0, 0),
                result(2, true, 5, 10),
                result(3, true, 4, 10)), users, contest));

            assertEquals(1, rows.get(2L).getGroupRank());
            assertEquals(2, rows.get(3L).getGroupRank());
        }

        @Test
        @DisplayName("a member removed mid-refresh is dropped rather than crashing the board")
        void unknownUserIsSkipped() {
            var rows = service.rank(List.of(result(99, true, 1, 1)), users, contest);
            assertTrue(rows.isEmpty());
        }
    }

    @Nested
    @DisplayName("Finding people on the judge")
    class Handles {

        private GroupMember member(long userId, String override) {
            return GroupMember.builder()
                .user(users.get(userId))
                .externalHandle(override)
                .build();
        }

        @Test
        @DisplayName("Codeforces uses the linked account before any override")
        void linkedAccountWins() {
            when(accounts.findByUserUserIdAndPlatform(1L, "CODEFORCES"))
                .thenReturn(Optional.of(PlatformAccount.builder().handle("tourist").build()));

            assertEquals("tourist", service.handleFor(contest, member(1, "ignored")));
        }

        @Test
        @DisplayName("Codeforces falls back to the override when nothing is linked")
        void overrideIsTheFallback() {
            when(accounts.findByUserUserIdAndPlatform(anyLong(), anyString()))
                .thenReturn(Optional.empty());

            assertEquals("manual", service.handleFor(contest, member(1, "manual")));
        }

        @Test
        @DisplayName("DOMjudge uses only the override, because there is nothing to derive from")
        void domjudgeNeedsAnOverride() {
            contest.setPlatform("DOMJUDGE");
            // The linked Codeforces account must not be used as a DOMjudge team name — it
            // would match the wrong person, or nobody, and look like a scoring bug.
            when(accounts.findByUserUserIdAndPlatform(anyLong(), anyString()))
                .thenReturn(Optional.of(PlatformAccount.builder().handle("tourist").build()));

            assertEquals("Team Alpha", service.handleFor(contest, member(1, "Team Alpha")));
            assertNull(service.handleFor(contest, member(2, null)));
        }
    }
}
