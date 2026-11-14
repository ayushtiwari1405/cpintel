package com.cpintel.scheduler;

import com.cpintel.labs.LabCheckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Looks for new lab solves on a schedule: every open lab, and every lab up to a week past its
 * deadline (for late solves).
 *
 * <p>Ten minutes by default. LeetCode only shows a user's last twenty accepted submissions,
 * so the interval has to be shorter than the time it takes anyone to solve twenty problems.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LabScheduler {

    private final LabCheckService checker;

    @Scheduled(fixedDelayString = "${cpintel.labs.check-interval-ms:600000}",
               initialDelayString = "${cpintel.labs.check-initial-delay-ms:120000}")
    // A class of two hundred is minutes of paced calls; lockAtLeastFor keeps a second instance
    // from starting the same pass on the same tick.
    @SchedulerLock(name = "checkLabs", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    public void checkLabs() {
        try {
            int recorded = checker.checkAll();
            if (recorded > 0) log.info("Lab check recorded {} solve(s)", recorded);
        } catch (Exception e) {
            log.warn("Lab check failed: {}", e.getMessage());
        }
    }
}
