package com.cpintel.groups;

import com.cpintel.entity.GroupContest;
import com.cpintel.entity.GroupMember;
import com.cpintel.entity.GroupStanding;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.integration.domjudge.DomjudgeCredentialStore;
import com.cpintel.repository.jpa.GroupMemberRepository;
import com.cpintel.repository.jpa.GroupStandingRepository;
import com.cpintel.repository.jpa.PlatformAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns an external scoreboard into a ranking of the group.
 *
 * The ranking itself is the interesting part and it is deliberately simple: more problems
 * first, then fewer penalty minutes, then a stable tie-break on username so the same inputs
 * always produce the same board. Ties share a rank in the usual competition way — two people
 * on second place are both second, and the next is fourth — because telling two people with
 * identical results that one of them is ahead would be inventing a distinction the contest did
 * not make.
 *
 * Members who could not be found on the external board are left unranked rather than ranked
 * last. A missing row almost always means a handle that does not match, and quietly sorting
 * that person to the bottom turns a configuration mistake into what looks like a bad result.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StandingsService {

    private final GroupMemberRepository memberRepository;
    private final GroupStandingRepository standingRepository;
    private final PlatformAccountRepository platformAccountRepository;
    private final List<StandingsProvider> providers;
    private final DomjudgeCredentialStore domjudgeCredentials;

    /**
     * Rebuilds the cached board for one contest.
     *
     * Expensive by nature — on Codeforces it is one rate-limited call per member — so it is
     * called on a schedule and by an explicit refresh, never from a page load.
     */
    @Transactional
    public void refresh(GroupContest contest) {
        List<GroupMember> members = memberRepository.findByGroup(contest.getGroup().getGroupId());
        if (members.isEmpty()) {
            standingRepository.deleteByContestContestId(contest.getContestId());
            contest.setStandingsRefreshedAt(Instant.now());
            contest.setStandingsError(null);
            return;
        }

        StandingsProvider provider = providerFor(contest.getPlatform());
        List<StandingsProvider.Competitor> competitors = members.stream()
            .map(member -> new StandingsProvider.Competitor(
                member.getUser().getUserId(), handleFor(contest, member),
                teamIdFor(contest, member)))
            .toList();

        List<StandingsProvider.Result> results;
        try {
            results = provider.fetch(contest, competitors);
        } catch (Exception e) {
            // The previous snapshot is kept and the reason recorded beside it. Showing stale
            // numbers as if they were current is the one outcome worth avoiding here, and the
            // board reports both the error and the age of what it is displaying.
            log.warn("Standings refresh failed for contest {}: {}",
                contest.getContestId(), e.getMessage());
            contest.setStandingsError(shorten(e.getMessage()));
            contest.setStandingsRefreshedAt(Instant.now());
            return;
        }

        Map<Long, User> userById = new HashMap<>();
        for (GroupMember member : members) userById.put(member.getUser().getUserId(), member.getUser());

        List<GroupStanding> rows = rank(results, userById, contest);

        standingRepository.deleteByContestContestId(contest.getContestId());
        standingRepository.flush();
        standingRepository.saveAll(rows);

        contest.setStandingsRefreshedAt(Instant.now());
        contest.setStandingsError(null);
    }

    /**
     * Orders the results and assigns ranks.
     *
     * Package-private and free of any I/O so the ordering rules can be tested directly — the
     * tie handling in particular is the sort of thing that looks right and is wrong.
     */
    List<GroupStanding> rank(List<StandingsProvider.Result> results,
                             Map<Long, User> userById,
                             GroupContest contest) {
        List<StandingsProvider.Result> ranked = new ArrayList<>(results.stream()
            .filter(StandingsProvider.Result::found)
            .toList());

        ranked.sort(Comparator
            .comparingInt(StandingsProvider.Result::solved).reversed()
            .thenComparingInt(StandingsProvider.Result::penalty)
            .thenComparing(result -> usernameOf(userById, result.userId())));

        Map<Long, Integer> rankByUser = new HashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            StandingsProvider.Result current = ranked.get(i);
            if (i > 0 && sameResult(ranked.get(i - 1), current)) {
                // Equal results share a rank; the next distinct result skips the numbers the
                // tie consumed, so position in the list still means something.
                rankByUser.put(current.userId(), rankByUser.get(ranked.get(i - 1).userId()));
            } else {
                rankByUser.put(current.userId(), i + 1);
            }
        }

        List<GroupStanding> rows = new ArrayList<>(results.size());
        for (StandingsProvider.Result result : results) {
            User user = userById.get(result.userId());
            if (user == null) continue;   // removed from the group mid-refresh

            rows.add(GroupStanding.builder()
                .contest(contest)
                .user(user)
                .handle(result.handle())
                .groupRank(rankByUser.get(result.userId()))
                .solved(result.solved())
                .penalty(result.penalty())
                .score(result.score() == null ? null : BigDecimal.valueOf(result.score()))
                .detail(result.detail())
                .found(result.found())
                .computedAt(Instant.now())
                .build());
        }
        return rows;
    }

    private boolean sameResult(StandingsProvider.Result a, StandingsProvider.Result b) {
        return a.solved() == b.solved() && a.penalty() == b.penalty();
    }

    private String usernameOf(Map<Long, User> userById, Long userId) {
        User user = userById.get(userId);
        return user == null ? "" : user.getUsername();
    }

    /**
     * The name to look this member up under on the judge running the contest.
     *
     * On Codeforces the linked platform account is the natural answer and the override is a
     * fallback for someone who has not linked one. On DOMjudge there is nothing to derive from
     * — CPIntel has no concept of a DOMjudge team — so the override is the only answer, and a
     * member without one is reported as unmatched rather than silently scored zero.
     */
    String handleFor(GroupContest contest, GroupMember member) {
        if (GroupContest.Platform.CODEFORCES.name().equals(contest.getPlatform())) {
            String linked = platformAccountRepository
                .findByUserUserIdAndPlatform(member.getUser().getUserId(), "CODEFORCES")
                .map(account -> account.getHandle())
                .orElse(null);
            if (StringUtils.hasText(linked)) return linked;
        }
        return StringUtils.hasText(member.getExternalHandle()) ? member.getExternalHandle() : null;
    }

    /**
     * The exact DOMjudge team to measure this member on, when one is known.
     *
     * Comes from the account an admin attached — their assigned team if they chose one, the
     * judge's otherwise. This is what makes the admin's choice mean something: without it the
     * board would fall back to matching {@code externalHandle} against team names, and the
     * assignment would be a field nothing reads.
     *
     * Null for Codeforces, and for a DOMjudge member with no account attached, both of which
     * fall back to name matching as before.
     */
    private String teamIdFor(GroupContest contest, GroupMember member) {
        if (!GroupContest.Platform.DOMJUDGE.name().equals(contest.getPlatform())) return null;

        DomjudgeCredentialStore.Stored stored =
            domjudgeCredentials.find(member.getUser().getUserId());
        return stored == null ? null : stored.effectiveTeamId();
    }

    private StandingsProvider providerFor(String platform) {
        return providers.stream()
            .filter(provider -> provider.platform().equals(platform))
            .findFirst()
            .orElseThrow(() -> ApiException.badRequest("No standings provider for " + platform));
    }

    private String shorten(String message) {
        if (message == null) return "The judge could not be read.";
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
