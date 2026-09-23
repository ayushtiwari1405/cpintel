package com.cpintel.events;

import com.cpintel.entity.ExamEvent;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.User;
import com.cpintel.groups.ContestMonitorRegistry;
import com.cpintel.repository.jpa.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The invigilator's live view of one examination.
 *
 * <p>Built from three sources, each answering something the others cannot. The event log says
 * what a candidate has done — entered, opened a problem, submitted — and how their session has
 * gone. The monitor heartbeat says whether anything is reporting from their machine <em>right
 * now</em>, which the log cannot answer because silence from somebody behaving perfectly and
 * silence from somebody who closed the window look identical in a log. The roster says who was
 * expected, which is how a candidate who never arrived appears at all.
 *
 * <p>Every column is an observation. Nothing here is a score, and nothing is a verdict: an
 * away time of two minutes is two minutes away from the window, and what that means is for the
 * person reading the screen to decide. Collapsing it into a single suspicion figure would be
 * easy and would be read as a finding by everyone who saw it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExamMonitorService {

    private final EventService events;
    private final ExamEventService examEvents;
    private final ContestMonitorRegistry monitors;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public EventsDto.MonitorSnapshot snapshot(Long eventId) {
        GroupContest event = events.require(eventId);
        Instant now = Instant.now();

        Set<Long> expected = events.participantIds(eventId);
        Map<Long, Map<String, long[]>> totals = examEvents.summarise(eventId);
        Map<Long, ExamEvent> latest = new HashMap<>();
        for (ExamEvent row : examEvents.latestPerUser(eventId)) {
            latest.put(row.getUser().getUserId(), row);
        }

        // Anyone who produced events is shown even if the roster no longer holds them — a
        // candidate removed from a team mid-examination has still sat it, and hiding their
        // session would quietly delete the part of the record that mattered.
        List<Long> ids = new ArrayList<>(expected);
        latest.keySet().stream().filter(id -> !expected.contains(id)).forEach(ids::add);

        Map<Long, User> users = new HashMap<>();
        for (User user : userRepository.findAllById(ids)) users.put(user.getUserId(), user);

        boolean live = event.isOpenForParticipation(now);
        List<EventsDto.MonitorRow> rows = new ArrayList<>(ids.size());

        for (Long userId : ids) {
            User user = users.get(userId);
            if (user == null) continue;

            Map<String, long[]> byType = totals.getOrDefault(userId, Map.of());
            ExamEvent last = latest.get(userId);

            int focusLosses = count(byType, ExamEvent.Type.FOCUS_LOST);
            long awayMs = duration(byType, ExamEvent.Type.FOCUS_REGAINED);
            int submissions = count(byType, ExamEvent.Type.PROBLEM_SUBMITTED);
            int attempted = count(byType, ExamEvent.Type.PROBLEM_OPENED);
            int total = byType.values().stream().mapToInt(v -> (int) v[0]).sum();

            boolean alive = monitors.isMonitored(userId, eventId);
            boolean away = last != null
                && ExamEvent.Type.FOCUS_LOST.name().equals(last.getType());

            rows.add(new EventsDto.MonitorRow(
                userId,
                user.getUsername(),
                user.getFullName(),
                statusOf(last, away, alive, live),
                alive,
                !away,
                focusLosses,
                // An absence still running is not in the total yet — it has no duration until
                // somebody comes back — so it is added here rather than being left out until
                // the moment it stops being the interesting one.
                away && last.getOccurredAt() != null
                    ? awayMs + Math.max(0, now.toEpochMilli() - last.getOccurredAt().toEpochMilli())
                    : awayMs,
                last == null ? null : last.getOccurredAt(),
                submissions,
                currentProblem(last),
                attempted,
                total));
        }

        rows.sort(Comparator.comparing(EventsDto.MonitorRow::username,
            String.CASE_INSENSITIVE_ORDER));

        int present = (int) rows.stream()
            .filter(r -> "ACTIVE".equals(r.status()) || "AWAY".equals(r.status())).count();
        int away = (int) rows.stream().filter(r -> "AWAY".equals(r.status())).count();
        int notStarted = (int) rows.stream().filter(r -> "NOT_STARTED".equals(r.status())).count();

        return new EventsDto.MonitorSnapshot(
            events.summarise(event, now), rows, now, expected.size(), present, away, notStarted);
    }

    /**
     * What this candidate looks like right now.
     *
     * <p>Ordered so the more specific answer wins. Having finished is a fact about the session
     * and outranks anything about focus; having left outranks being away, because leaving is
     * where an absence ends up if it never ends. "ACTIVE with no monitor" is deliberately
     * distinguished from "LEFT" only while the examination is live — afterwards a stopped
     * monitor is just a finished session.
     */
    private String statusOf(ExamEvent last, boolean away, boolean monitorAlive, boolean live) {
        if (last == null) return "NOT_STARTED";

        ExamEvent.Type type;
        try {
            type = ExamEvent.Type.valueOf(last.getType());
        } catch (IllegalArgumentException e) {
            return "ACTIVE";
        }

        return switch (type) {
            case EXAM_COMPLETED -> "SUBMITTED";
            case EXAM_EXITED, SESSION_TERMINATED -> "LEFT";
            default -> {
                if (away) yield "AWAY";
                if (live && !monitorAlive) yield "LEFT";
                yield "ACTIVE";
            }
        };
    }

    /** The problem they were last seen on, when that is what they were last doing. */
    private String currentProblem(ExamEvent last) {
        if (last == null || last.getProblemLabel() == null) return null;
        return last.getProblemLabel();
    }

    private int count(Map<String, long[]> byType, ExamEvent.Type type) {
        long[] row = byType.get(type.name());
        return row == null ? 0 : (int) row[0];
    }

    private long duration(Map<String, long[]> byType, ExamEvent.Type type) {
        long[] row = byType.get(type.name());
        return row == null ? 0 : row[1];
    }
}
