package com.cpintel.scheduler;

import com.cpintel.events.ExamEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Enforces the examination log's retention policy.
 *
 * <p>An examination log is evidence about a person, and keeping it forever by default is a
 * decision nobody made. The deployment sets a window; this is what makes the window real.
 *
 * <p>Daily, and locked so that two replicas do not both sweep — the delete is idempotent, but a
 * second instance scanning the same rows costs a table scan for nothing. It is deliberately not
 * run at startup: a deployment that had just lowered its retention would otherwise delete a
 * year of sessions as a side effect of a restart, which is not a moment anybody is watching.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExamRetentionScheduler {

    private final ExamEventService examEvents;

    @Scheduled(fixedDelayString = "${cpintel.exams.retention-sweep-ms:86400000}",
               initialDelayString = "${cpintel.exams.retention-initial-delay-ms:3600000}")
    @SchedulerLock(name = "purgeExamEvents", lockAtMostFor = "PT30M", lockAtLeastFor = "PT10M")
    public void purge() {
        try {
            examEvents.purgeExpired();
        } catch (Exception e) {
            // A failed sweep means rows live longer than intended, which is recoverable on the
            // next run. Failing the scheduler thread would stop every later sweep as well.
            log.warn("Examination log retention sweep failed: {}", e.getMessage());
        }
    }
}
