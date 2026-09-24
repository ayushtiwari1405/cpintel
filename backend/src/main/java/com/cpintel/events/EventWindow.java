package com.cpintel.events;

import com.cpintel.compete.CompeteDto;
import com.cpintel.entity.GroupContest;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.GroupContestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * When a judge contest is open to somebody CPIntel set an event on it for.
 *
 * <p>The judge's own window is not the answer. A DOMjudge contest is often left running for
 * weeks and reused — a practice round one week, a paper the next — while each event over it
 * has its own start and end. Somebody who was given an event on the contest sits it for that
 * event's window, and once it is over the contest is over for them, whatever the judge's clock
 * says. Somebody with no event on it keeps the judge's window: nothing was set for them.
 */
@Service
@RequiredArgsConstructor
public class EventWindow {

    private final GroupContestRepository events;

    /**
     * The event that decides this person's window on this judge contest: the one running now,
     * else the next to start, else the one that ended last. Empty when they hold none with a
     * full window.
     */
    @Transactional(readOnly = true)
    public Optional<GroupContest> governing(Long userId, String platform, String contestId) {
        Instant now = Instant.now();
        List<GroupContest> held = events.findForParticipant(
                userId, platform.toUpperCase(Locale.ROOT), contestId).stream()
            .filter(e -> e.getStartsAt() != null && e.getEndsAt() != null)
            .toList();

        return held.stream()
            .filter(e -> e.effectiveLifecycle(now) == GroupContest.Lifecycle.ACTIVE)
            .findFirst()
            .or(() -> held.stream()
                .filter(e -> e.effectiveLifecycle(now) == GroupContest.Lifecycle.SCHEDULED)
                .min(Comparator.comparing(GroupContest::getStartsAt)))
            .or(() -> held.stream().max(Comparator.comparing(GroupContest::getEndsAt)));
    }

    /** The judge's view of the contest, with the clock replaced by the event's. */
    public CompeteDto.ContestInfo apply(Long userId, CompeteDto.ContestInfo info) {
        return governing(userId, info.platform(), info.id())
            .map(event -> windowed(info, event, Instant.now()))
            .orElse(info);
    }

    /** Throws unless this person's event on this contest is running. */
    public void requireOpen(Long userId, String platform, String contestId) {
        Instant now = Instant.now();
        governing(userId, platform, contestId)
            .filter(event -> !event.isOpenForParticipation(now))
            .ifPresent(event -> {
                throw ApiException.forbidden(closedReason(event, now));
            });
    }

    static CompeteDto.ContestInfo windowed(CompeteDto.ContestInfo info, GroupContest event,
                                           Instant now) {
        GroupContest.Lifecycle stage = event.effectiveLifecycle(now);
        boolean running = stage == GroupContest.Lifecycle.ACTIVE;
        String phase = switch (stage) {
            case ACTIVE -> "CODING";
            case SCHEDULED -> "BEFORE";
            default -> "FINISHED";
        };
        long untilStart = Duration.between(now, event.getStartsAt()).getSeconds();
        long remaining = Math.max(0, Duration.between(now, event.getEndsAt()).getSeconds());

        return new CompeteDto.ContestInfo(
            info.id(), info.name(), info.platform(), phase, running, info.frozen(),
            event.getStartsAt(),
            Duration.between(event.getStartsAt(), event.getEndsAt()).getSeconds(),
            untilStart, running ? remaining : 0,
            running && info.submissionsOpen(),
            running ? info.submissionsClosedReason() : closedReason(event, now),
            info.personalFilesEnabled(), info.statementFormat(), info.problems(), info.url());
    }

    private static String closedReason(GroupContest event, Instant now) {
        return event.effectiveLifecycle(now) == GroupContest.Lifecycle.SCHEDULED
            ? "\"" + event.getName() + "\" has not started yet."
            : "\"" + event.getName() + "\" has ended, so no more submissions are accepted.";
    }
}
