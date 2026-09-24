package com.cpintel.files;

import com.cpintel.entity.GroupContest;
import com.cpintel.repository.jpa.GroupContestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Gives every event created before private files were decided per event a rule of its own.
 *
 * <p>Those events followed the deployment-wide default, so moving the default changed them —
 * including a paper already scheduled, or one being sat. Each is pinned to what it gets today:
 * nothing anybody sees changes now, and nothing changes under them later. New events are given
 * a rule when they are created (see EventService), so after one run this finds nothing to do.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ContestFileRuleBackfill implements ApplicationRunner {

    private final GroupContestRepository events;
    private final ContestFilePolicy policy;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int pinned = 0;
            Set<String> seen = new HashSet<>();
            for (GroupContest event : events.findAll()) {
                String platform = event.getPlatform();
                String contestId = event.getExternalId();
                if (platform == null || contestId == null) continue;
                if (!seen.add(platform + "/" + contestId)) continue;
                if (policy.hasRule(platform, contestId)) continue;

                boolean now = policy.enabledFor(platform, contestId);
                policy.setRule(platform, contestId, null, new FilesDto.RuleRequest(now,
                    "Pinned to the default in force when events got their own rule"));
                pinned++;
            }
            if (pinned > 0) {
                log.info("Pinned the private-files rule for {} existing event contest(s)", pinned);
            }
        } catch (Exception e) {
            log.warn("Could not backfill private-files rules: {}", e.getMessage());
        }
    }
}
