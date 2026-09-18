package com.cpintel.scheduler;

import com.cpintel.entity.GroupContest;
import com.cpintel.groups.StandingsService;
import com.cpintel.repository.jpa.GroupContestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

    @Scheduled(fixedDelayString = "${cpintel.groups.standings-refresh-ms:180000}",
               initialDelayString = "${cpintel.groups.standings-initial-delay-ms:60000}")
    // The shortest interval of the five and the one that writes shared rows, so this is the job
    // two replicas would have corrupted first. lockAtMostFor exceeds the refresh interval: a
    // group of thirty is thirty rate-limited calls, which now queue on the outbound limiter.
    // lockAtLeastFor is just under the 3-minute refresh interval: this job returns immediately
    // when no contest is live, so without a floor the lock would be free again instantly and a
    // second instance would refresh the same boards on the same tick.
    @SchedulerLock(name = "refreshLiveContests", lockAtMostFor = "PT10M", lockAtLeastFor = "PT2M")
    @Transactional
    public void refreshLiveContests() {
        List<GroupContest> live = contestRepository.findLive(Instant.now());
        if (live.isEmpty()) return;

        log.debug("Refreshing standings for {} live group contest(s)", live.size());
        for (GroupContest contest : live) {
            try {
                standingsService.refresh(contest);
                contestRepository.save(contest);
            } catch (Exception e) {
                // One unreachable judge must not stop the others being refreshed. The failure
                // is already recorded on the contest row by the service.
                log.warn("Standings refresh failed for contest {}: {}",
                    contest.getContestId(), e.getMessage());
            }
        }
    }
}
