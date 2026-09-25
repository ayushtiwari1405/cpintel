package com.cpintel.events;

import com.cpintel.entity.AuditLog;
import com.cpintel.entity.ExamEvent;
import com.cpintel.entity.User;
import com.cpintel.repository.jpa.AuditLogRepository;
import com.cpintel.repository.jpa.ExamEventRepository;
import com.cpintel.repository.jpa.UserRepository;
import com.cpintel.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The examination's suspicious-activity list: the few rows out of thousands worth a human look.
 *
 * <p>Derived from the session log and the sign-in trail on every read, never stored. That keeps
 * it a view over observations rather than a record of accusations — change a threshold and
 * the list for last month's paper changes with it, and nothing is ever written against a
 * candidate's name.
 *
 * <p>Each flag says what was seen and by how much it passed the threshold. Whether a candidate
 * who was away for four minutes was cheating or ill is, as everywhere else in the monitor, for
 * the person reading it.
 */
@Service
@RequiredArgsConstructor
public class ExamFlagService {

    private static final List<String> TYPES = List.of(
        ExamEvent.Type.EXAM_ENTERED.name(),
        ExamEvent.Type.EXAM_STARTED.name(),
        ExamEvent.Type.PROBLEM_OPENED.name(),
        ExamEvent.Type.PROBLEM_SUBMITTED.name(),
        ExamEvent.Type.FOCUS_LOST.name(),
        ExamEvent.Type.FOCUS_REGAINED.name(),
        ExamEvent.Type.LOCKDOWN_TRIGGERED.name(),
        ExamEvent.Type.SUSPICIOUS_ACTIVITY.name());

    private final EventService events;
    private final ExamEventRepository examEvents;
    private final AuditLogRepository auditLog;
    private final UserRepository userRepository;

    /** One absence at least this long is flagged. */
    @Value("${cpintel.exams.flags.long-away-seconds:60}")
    private int longAwaySeconds = 60;

    /** This many focus losses in one session is flagged. */
    @Value("${cpintel.exams.flags.frequent-away-count:5}")
    private int frequentAwayCount = 5;

    /** A first submission on a problem sooner than this after opening it is flagged. */
    @Value("${cpintel.exams.flags.fast-submission-seconds:30}")
    private int fastSubmissionSeconds = 30;

    @Transactional(readOnly = true)
    public EventsDto.FlagReport flags(Long eventId) {
        events.require(eventId);

        List<ExamEvent> rows = examEvents.findTypesForContest(eventId, TYPES);
        Map<Long, User> users = new HashMap<>();
        Map<Long, List<ExamEvent>> byUser = new LinkedHashMap<>();
        for (ExamEvent row : rows) {
            users.putIfAbsent(row.getUser().getUserId(), row.getUser());
            byUser.computeIfAbsent(row.getUser().getUserId(), k -> new ArrayList<>()).add(row);
        }

        List<EventsDto.Flag> flags = new ArrayList<>();
        byUser.forEach((userId, session) -> flagSession(users.get(userId), session, flags));
        flagSignIns(eventId, users, flags);

        flags.sort(Comparator.comparing(EventsDto.Flag::occurredAt,
            Comparator.nullsLast(Comparator.reverseOrder())));
        return new EventsDto.FlagReport(
            flags, longAwaySeconds, frequentAwayCount, fastSubmissionSeconds);
    }

    /** Everything that can be read off one candidate's own log, which arrives oldest first. */
    private void flagSession(User user, List<ExamEvent> session, List<EventsDto.Flag> out) {
        long longAwayMs = longAwaySeconds * 1000L;
        int focusLosses = 0;
        Instant crossedAt = null;
        int lockdowns = 0;
        Instant lastLockdown = null;
        Instant entered = null;
        Map<String, Instant> firstOpened = new HashMap<>();
        Set<String> submitted = new HashSet<>();

        for (ExamEvent row : session) {
            ExamEvent.Type type;
            try {
                type = ExamEvent.Type.valueOf(row.getType());
            } catch (IllegalArgumentException e) {
                continue;
            }

            switch (type) {
                case EXAM_ENTERED, EXAM_STARTED -> {
                    if (entered == null) entered = row.getOccurredAt();
                }
                case PROBLEM_OPENED -> {
                    if (row.getProblemLabel() != null) {
                        firstOpened.putIfAbsent(row.getProblemLabel(), row.getOccurredAt());
                    }
                }
                case FOCUS_REGAINED -> {
                    Long away = row.getDurationMs();
                    if (away != null && away >= longAwayMs) {
                        out.add(flag(user, "LONG_AWAY", away >= longAwayMs * 5 ? "HIGH" : "MEDIUM",
                            row.getProblemLabel(),
                            "Away from the examination for " + human(away),
                            row.getOccurredAt()));
                    }
                }
                case FOCUS_LOST -> {
                    focusLosses++;
                    // Flagged once, dated at the loss that crossed the line.
                    if (focusLosses == frequentAwayCount) crossedAt = row.getOccurredAt();
                }
                case PROBLEM_SUBMITTED -> {
                    String label = row.getProblemLabel();
                    // Only the first submission on a problem: a resubmission a few seconds
                    // after a wrong answer is how everybody works.
                    if (label == null || !submitted.add(label)) break;
                    Instant since = firstOpened.getOrDefault(label, entered);
                    if (since == null || row.getOccurredAt() == null) break;
                    long seconds = Duration.between(since, row.getOccurredAt()).toSeconds();
                    if (seconds >= 0 && seconds < fastSubmissionSeconds) {
                        out.add(flag(user, "FAST_SUBMISSION",
                            seconds < fastSubmissionSeconds / 3 ? "HIGH" : "MEDIUM", label,
                            "First submission on " + label + " " + seconds + "s after "
                                + (firstOpened.containsKey(label)
                                    ? "opening it" : "entering the examination"),
                            row.getOccurredAt()));
                    }
                }
                case LOCKDOWN_TRIGGERED -> {
                    lockdowns++;
                    lastLockdown = row.getOccurredAt();
                }
                case SUSPICIOUS_ACTIVITY -> out.add(flag(user, "FLAGGED", "MEDIUM",
                    row.getProblemLabel(), row.getDetail(), row.getOccurredAt()));
                default -> { }
            }
        }

        if (focusLosses >= frequentAwayCount) {
            out.add(flag(user, "FREQUENT_AWAY",
                focusLosses >= frequentAwayCount * 3 ? "HIGH" : "MEDIUM", null,
                "Left the examination window " + focusLosses + " times", crossedAt));
        }
        if (lockdowns > 0) {
            out.add(flag(user, "LOCKDOWN", lockdowns >= 3 ? "HIGH" : "MEDIUM", null,
                "A desktop restriction fired " + lockdowns + " time" + (lockdowns == 1 ? "" : "s"),
                lastLockdown));
        }
    }

    /**
     * Signing in to the same paper more than once.
     *
     * <p>A crash and a restart is an ordinary reason for two; two different addresses is the
     * case that most deserves a look, since that is what a second person using the same slip
     * looks like.
     */
    private void flagSignIns(Long eventId, Map<Long, User> users, List<EventsDto.Flag> out) {
        Map<Long, List<AuditLog>> byUser = new LinkedHashMap<>();
        for (AuditLog row : auditLog.findByActionAndEntityTypeAndEntityIdOrderByCreatedAtAsc(
                AuditService.EXAM_SIGN_IN, "EXAM", String.valueOf(eventId))) {
            if (row.getUserId() == null) continue;
            byUser.computeIfAbsent(row.getUserId(), k -> new ArrayList<>()).add(row);
        }
        byUser.values().removeIf(list -> list.size() < 2);
        if (byUser.isEmpty()) return;

        Set<Long> missing = new HashSet<>(byUser.keySet());
        missing.removeAll(users.keySet());
        if (!missing.isEmpty()) {
            for (User user : userRepository.findAllById(missing)) users.put(user.getUserId(), user);
        }

        byUser.forEach((userId, signIns) -> {
            User user = users.get(userId);
            if (user == null) return;
            long addresses = signIns.stream().map(AuditLog::getIpAddress)
                .filter(Objects::nonNull).distinct().count();
            out.add(flag(user, "MULTIPLE_SIGN_INS", addresses > 1 ? "HIGH" : "MEDIUM", null,
                "Signed in " + signIns.size() + " times"
                    + (addresses > 1 ? " from " + addresses + " different addresses" : ""),
                signIns.get(signIns.size() - 1).getCreatedAt()));
        });
    }

    private static EventsDto.Flag flag(User user, String kind, String severity, String problem,
                                       String detail, Instant at) {
        return new EventsDto.Flag(user.getUserId(), user.getUsername(), user.getFullName(),
            kind, severity, problem, detail, at);
    }

    private static String human(long ms) {
        long total = Math.round(ms / 1000.0);
        if (total < 60) return total + "s";
        long minutes = total / 60;
        return total % 60 == 0 ? minutes + "m" : minutes + "m " + (total % 60) + "s";
    }
}
