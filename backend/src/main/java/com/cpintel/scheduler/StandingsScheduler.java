package com.cpintel.scheduler;

import com.cpintel.entity.GroupContest;
import com.cpintel.groups.StandingsService;
import com.cpintel.repository.jpa.GroupContestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * Keeps the group boards current while a contest is actually being sat.
 *
 * Only live contests are refreshed. A finished one does not change, and a scheduled one has
 * nothing to read yet — refreshing either would spend the Codeforces rate limit, which is one
 * call per member, on an answer nobody is waiting for.
 *
 * The interval is deliberately unhurried. A group of thirty costs thirty rate-limited calls,
 * so a board that lags the judge by a couple of minutes is the cost of not being throttled at
 * the moment the round ends and everyone is looking.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StandingsScheduler {

    private final GroupContestRepository contestRepository;
    private final StandingsService standingsService;
    private final PlatformTransactionManager transactions;

    @Scheduled(fixedDelayString = "${cpintel.groups.standings-refresh-ms:180000}",
               initialDelayString = "${cpintel.groups.standings-initial-delay-ms:60000}")
    // The shortest interval of the five and the one that writes shared rows, so this is the job
    // two replicas would have corrupted first. lockAtMostFor exceeds the refresh interval: a
    // group of thirty is thirty rate-limited calls, which now queue on the outbound limiter.
    // lockAtLeastFor is just under the 3-minute refresh interval: this job returns immediately
    // when no contest is live, so without a floor the lock would be free again instantly and a
    // second instance would refresh the same boards on the same tick.
    @SchedulerLock(name = "refreshLiveContests", lockAtMostFor = "PT10M", lockAtLeastFor = "PT2M")
    public void refreshLiveContests() {
        List<GroupContest> live = contestRepository.findLive(Instant.now());
        if (live.isEmpty()) return;

        // A transaction per contest. In one shared transaction, a single contest throwing marked
        // it rollback-only: every other board refreshed on the same tick was thrown away with
        // it, and the commit's failure escaped the catch below.
        TransactionTemplate perContest = new TransactionTemplate(transactions);
        perContest.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        log.debug("Refreshing standings for {} live group contest(s)", live.size());
        for (GroupContest found : live) {
            Long id = found.getContestId();
            try {
                perContest.executeWithoutResult(status -> contestRepository.findById(id)
                    // An event assigned to teams, rather than run for one group, has no group
                    // board: it is ranked from the submission archive on read.
                    .filter(contest -> contest.getGroup() != null)
                    .ifPresent(contest -> {
                        standingsService.refresh(contest);
                        contestRepository.save(contest);
                    }));
            } catch (Exception e) {
                // One unreachable judge must not stop the others being refreshed. The failure
                // is already recorded on the contest row by the service.
                log.warn("Standings refresh failed for contest {}: {}", id, e.getMessage());
            }
        }
    }
}
