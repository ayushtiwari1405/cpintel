package com.cpintel.events;

import com.cpintel.entity.ExamEvent;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.ExamEventRepository;
import com.cpintel.repository.jpa.GroupContestRepository;
import com.cpintel.repository.jpa.UserRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The examination log: everything that happened during a session, for everyone who sat it.
 *
 * <p>Two things about this are worth stating plainly, because both are easy to get wrong in a
 * way nobody notices until an examination has already been sat.
 *
 * <p><b>It records observations, not findings.</b> A focus loss is a focus loss. Whether it was
 * a second screen with the answer on it, a notification, or somebody answering the door is not
 * something the software can know, and nothing here computes a number that could be read as a
 * verdict. The types are named for what was seen.
 *
 * <p><b>It is only as trustworthy as its provenance.</b> A report is accepted only from the
 * person it is about, only for an examination they are actually sitting, and only with a
 * timestamp inside that examination's window plus a little slack. Without all three, a client
 * could file events against someone else or backdate its own, and the whole log would be worth
 * nothing precisely when somebody needed to rely on it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExamEventService {

    /** How long after an examination ends a late report is still accepted. */
    private static final long GRACE_MINUTES = 10;

    /** Nothing is accepted more than this far before it starts. */
    private static final long PRE_START_TOLERANCE_MINUTES = 30;

    private static final int MAX_EVENTS_PER_REPORT = 200;
    private static final int MAX_PAGE_SIZE = 500;

    private static final Set<String> TYPES = Arrays.stream(ExamEvent.Type.values())
        .map(Enum::name).collect(Collectors.toUnmodifiableSet());

    private final ExamEventRepository events;
    private final GroupContestRepository eventRepository;
    private final UserRepository userRepository;

    /**
     * How long a session log is kept.
     *
     * An examination record is evidence about a person, and keeping it forever by default is a
     * decision nobody made. A deployment sets its own retention; the sweep that enforces it is
     * in {@link ExamRetentionScheduler}.
     */
    @Value("${cpintel.exams.event-retention-days:365}")
    private int retentionDays;

    public int retentionDays() {
        return retentionDays;
    }

    // ----------------------------------------------------------- client reports

    /**
     * Records what a candidate's own client observed.
     *
     * Returns how many rows were newly stored. A retry of an already-accepted batch stores
     * nothing and is not an error, which is what lets a client with a flaky connection resend
     * its queue freely rather than choosing between losing events and duplicating them.
     */
    @Transactional
    public int report(Long userId, Long eventId, EventsDto.EventReport report) {
        GroupContest event = requireParticipant(userId, eventId);

        if (report.events() == null || report.events().isEmpty()) return 0;
        if (report.events().size() > MAX_EVENTS_PER_REPORT) {
            throw ApiException.badRequest(
                "Too many events in one report — send at most " + MAX_EVENTS_PER_REPORT + ".");
        }

        User user = userRepository.findById(userId)
            .orElseThrow(() -> ApiException.notFound("No such user"));

        Instant now = Instant.now();
        List<ExamEvent> toSave = new ArrayList<>();

        for (EventsDto.ClientEvent reported : report.events()) {
            String type = reported.type() == null
                ? "" : reported.type().trim().toUpperCase(Locale.ROOT);
            if (!TYPES.contains(type)) {
                // An unknown type from a newer or tampered client is dropped rather than
                // stored: an unreadable row in a log somebody will be judged against is worse
                // than no row.
                log.debug("Ignoring unknown exam event type {} from user {}", type, userId);
                continue;
            }

            Instant occurredAt = clampToWindow(reported.occurredAt(), event, now);
            if (occurredAt == null) continue;

            // The unique index is the real guard; this only avoids the round trip in the
            // common case of a wholesale retry.
            if (events.existsByContestContestIdAndUserUserIdAndEventId(
                    eventId, userId, reported.eventId())) {
                continue;
            }

            toSave.add(ExamEvent.builder()
                .contest(event)
                .user(user)
                .eventId(reported.eventId())
                .type(type)
                .problemLabel(reported.problemLabel())
                .durationMs(reported.durationMs())
                .detail(reported.detail())
                .occurredAt(occurredAt)
                .recordedAt(now)
                .build());
        }

        if (toSave.isEmpty()) return 0;

        try {
            events.saveAll(toSave);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Two retries racing. The unique index did its job and nothing is lost.
            log.debug("Duplicate exam event batch from user {} on event {}", userId, eventId);
            return 0;
        }
        return toSave.size();
    }

    /**
     * Records something CPIntel itself observed, rather than something a client claimed.
     *
     * Entering an examination and receiving a verdict are known to the server, and a server-side
     * row cannot be withheld by a candidate who would rather it were not there. The event id is
     * generated here, so these are idempotent only in the sense that the server writes them once.
     */
    @Transactional
    public void recordServerSide(GroupContest event, Long userId, ExamEvent.Type type,
                                 String problemLabel, String detail) {
        try {
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) return;

            events.save(ExamEvent.builder()
                .contest(event)
                .user(user)
                .eventId("srv-" + UUID.randomUUID())
                .type(type.name())
                .problemLabel(problemLabel)
                .detail(detail)
                .occurredAt(Instant.now())
                .recordedAt(Instant.now())
                .build());
        } catch (Exception e) {
            // A log write must never be the reason a candidate cannot enter or submit.
            log.warn("Could not record {} for user {} on event {}: {}",
                type, userId, event.getContestId(), e.getMessage());
        }
    }

    // -------------------------------------------------------------- admin reads

    /**
     * The log, filtered the way an invigilator reads it.
     *
     * A team filter is resolved to user ids by the caller rather than joined here — which is
     * also what makes "this class, plus these three individuals" expressible at all.
     */
    public EventsDto.LogPage search(GroupContest event, List<Long> userIds, String type,
                                    Instant from, Instant to, int page, int size) {
        int capped = Math.clamp(size, 1, MAX_PAGE_SIZE);
        String wantedType = type == null || type.isBlank()
            ? null : type.trim().toUpperCase(Locale.ROOT);
        List<Long> users = userIds == null || userIds.isEmpty() ? null : userIds;

        Page<ExamEvent> found = events.findAll(
            filter(event.getContestId(), users, wantedType, from, to),
            PageRequest.of(Math.max(page, 0), capped,
                Sort.by(Sort.Direction.DESC, "occurredAt")));

        List<EventsDto.LogEntry> entries = found.getContent().stream()
            .map(row -> new EventsDto.LogEntry(
                row.getEventRowId(),
                row.getUser().getUserId(),
                row.getUser().getUsername(),
                row.getType(),
                row.getProblemLabel(),
                row.getDurationMs(),
                row.getDetail(),
                row.getOccurredAt(),
                row.getRecordedAt()))
            .toList();

        return new EventsDto.LogPage(
            null,                    // filled in by the caller, which has the summary already
            entries,
            found.getNumber(),
            found.getSize(),
            found.getTotalElements(),
            found.getTotalPages(),
            Arrays.stream(ExamEvent.Type.values()).map(Enum::name).sorted().toList(),
            retentionDays);
    }

    /**
     * The filters an invigilator actually asked for, and no others.
     *
     * Built rather than written as JPQL with a null check per filter, which PostgreSQL refuses:
     * a bare parameter compared against NULL has no inferrable type and the driver rejects the
     * statement at runtime. This also keeps one query shape instead of one per combination.
     */
    private Specification<ExamEvent> filter(Long contestId, List<Long> userIds, String type,
                                            Instant from, Instant to) {
        return (root, query, cb) -> {
            // The row list needs the candidate's name; the count query that Spring Data runs
            // beside it must not carry a fetch join, or the count itself fails.
            if (query != null && query.getResultType() != Long.class
                && query.getResultType() != long.class) {
                root.fetch("user");
            }

            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("contest").get("contestId"), contestId));
            if (type != null) where.add(cb.equal(root.get("type"), type));
            if (from != null) where.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            if (to != null) where.add(cb.lessThanOrEqualTo(root.get("occurredAt"), to));
            if (userIds != null) where.add(root.get("user").get("userId").in(userIds));
            return cb.and(where.toArray(new Predicate[0]));
        };
    }

    /** Per-type counts for one candidate, used for their own screen and for the dashboard. */
    public Map<String, Integer> countsFor(Long eventId, Long userId) {
        Map<String, Integer> counts = new HashMap<>();
        for (Object[] row : events.summariseByUser(eventId)) {
            if (!userId.equals(row[0])) continue;
            counts.merge((String) row[1], ((Number) row[2]).intValue(), Integer::sum);
        }
        return counts;
    }

    /** Raw per-user totals: userId → type → [count, summed duration]. One query. */
    public Map<Long, Map<String, long[]>> summarise(Long eventId) {
        Map<Long, Map<String, long[]>> byUser = new HashMap<>();
        for (Object[] row : events.summariseByUser(eventId)) {
            Long userId = (Long) row[0];
            String type = (String) row[1];
            long count = ((Number) row[2]).longValue();
            long duration = ((Number) row[3]).longValue();
            byUser.computeIfAbsent(userId, k -> new HashMap<>())
                .merge(type, new long[] { count, duration }, (a, b) -> {
                    a[0] += b[0];
                    a[1] += b[1];
                    return a;
                });
        }
        return byUser;
    }

    public List<ExamEvent> latestPerUser(Long eventId) {
        return events.latestPerUser(eventId);
    }

    // ------------------------------------------------------------- maintenance

    /** Drops sessions past the retention window. Returns how many rows went. */
    @Transactional
    public int purgeExpired() {
        if (retentionDays <= 0) return 0;
        Instant cutoff = Instant.now().minusSeconds(retentionDays * 86_400L);
        int removed = events.deleteRecordedBefore(cutoff);
        if (removed > 0) {
            log.info("Examination log retention: removed {} events recorded before {}",
                removed, cutoff);
        }
        return removed;
    }

    // ------------------------------------------------------------------ guards

    /**
     * The examination, if this person is actually sitting it.
     *
     * Assignment is the authorisation. Without this check anyone holding an id could file
     * events against an examination they are not in — and the log is the thing an invigilator
     * reads when deciding what happened to somebody.
     */
    public GroupContest requireParticipant(Long userId, Long eventId) {
        if (!eventRepository.isAssignedTo(eventId, userId)) {
            throw ApiException.notFound("No such contest or examination");
        }
        return eventRepository.findById(eventId)
            .orElseThrow(() -> ApiException.notFound("No such contest or examination"));
    }

    /**
     * Keeps a reported timestamp inside the examination's window.
     *
     * The clock belongs to the client, and a client that is wrong — or lying — must not be able
     * to place an event outside the session it belongs to. Anything wildly outside is dropped;
     * anything close is pulled to the boundary, because a few seconds of skew is ordinary and
     * discarding those would lose real events.
     */
    private Instant clampToWindow(Instant reported, GroupContest event, Instant now) {
        Instant when = reported == null ? now : reported;

        Instant start = event.getStartsAt();
        Instant end = event.getEndsAt();
        if (start == null || end == null) return when;

        Instant earliest = start.minusSeconds(PRE_START_TOLERANCE_MINUTES * 60);
        Instant latest = end.plusSeconds(GRACE_MINUTES * 60);

        if (when.isBefore(earliest) || when.isAfter(latest)) return null;
        if (when.isBefore(start)) return start;
        if (when.isAfter(end)) return end;
        return when;
    }
}
