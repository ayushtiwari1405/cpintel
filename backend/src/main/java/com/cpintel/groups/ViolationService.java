package com.cpintel.groups;

import com.cpintel.entity.ContestViolation;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.ContestViolationRepository;
import com.cpintel.repository.jpa.GroupContestRepository;
import com.cpintel.repository.jpa.GroupMemberRepository;
import com.cpintel.repository.jpa.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What the desktop lock reported during a group contest.
 *
 * The design principle here is that this records observations, not accusations. The lock can
 * see that a window lost focus for ninety seconds; it cannot see whether that was a second
 * monitor with the solution on it, a notification, or someone answering the door. Everything in
 * this class — the type names, the summary shape, the refusal to compute a single "suspicion
 * score" — exists to keep that distinction intact by the time an admin reads it, because the
 * moment it becomes one number somebody will treat that number as a verdict.
 *
 * Reports are accepted only from the person they are about, only for a contest they are
 * actually in, and only while the contest window is open plus a short grace. A client that
 * could report about someone else, or backdate an event, would make the whole record worthless.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ViolationService {

    /** How long after a contest ends a late report is still accepted. */
    private static final long GRACE_MINUTES = 10;

    /** Nothing is accepted more than this far before the contest starts. */
    private static final long PRE_START_TOLERANCE_MINUTES = 30;

    private static final int MAX_EVENTS_PER_REPORT = 200;

    private static final Set<String> TYPES = java.util.Arrays.stream(ContestViolation.Type.values())
        .map(Enum::name).collect(Collectors.toUnmodifiableSet());

    private final ContestViolationRepository violationRepository;
    private final GroupContestRepository contestRepository;
    private final GroupMemberRepository memberRepository;
    private final UserRepository userRepository;

    /**
     * Records what a participant's own client observed.
     *
     * Returns how many events were newly stored — a retry of an already-accepted batch stores
     * nothing and is not an error, which is what lets the desktop client retry freely.
     */
    @Transactional
    public int report(Long userId, Long contestId, GroupsDto.ViolationReport report) {
        GroupContest contest = contestRepository.findById(contestId)
            .orElseThrow(() -> ApiException.notFound("No such contest"));

        // Membership is the authorisation. Without it, anyone with a contest id could file
        // reports against a round they are not sitting.
        if (!memberRepository.existsByGroupGroupIdAndUserUserId(
                contest.getGroup().getGroupId(), userId)) {
            throw ApiException.forbidden("You are not in the group running this contest.");
        }

        if (report.events() == null || report.events().isEmpty()) return 0;
        if (report.events().size() > MAX_EVENTS_PER_REPORT) {
            throw ApiException.badRequest(
                "Too many events in one report — send at most " + MAX_EVENTS_PER_REPORT + ".");
        }

        User user = userRepository.findById(userId)
            .orElseThrow(() -> ApiException.notFound("No such user"));

        Instant now = Instant.now();
        List<ContestViolation> toSave = new ArrayList<>();

        for (GroupsDto.ViolationEvent event : report.events()) {
            String type = event.type() == null ? "" : event.type().trim().toUpperCase(Locale.ROOT);
            if (!TYPES.contains(type)) {
                // An unknown type from a newer or tampered client is dropped rather than
                // stored: an unreadable row in an evidence trail is worse than no row.
                log.debug("Ignoring unknown violation type {} from user {}", type, userId);
                continue;
            }

            Instant occurredAt = clampToContest(event.occurredAt(), contest, now);
            if (occurredAt == null) continue;

            // The unique index is the real guard; this check just avoids the round trip for
            // the common case of a wholesale retry.
            if (violationRepository.existsByContestContestIdAndUserUserIdAndEventId(
                    contestId, userId, event.eventId())) {
                continue;
            }

            toSave.add(ContestViolation.builder()
                .contest(contest)
                .user(user)
                .eventId(event.eventId())
                .type(type)
                .detail(event.detail())
                .durationMs(event.durationMs())
                .occurredAt(occurredAt)
                .reportedAt(now)
                .build());
        }

        if (toSave.isEmpty()) return 0;

        try {
            violationRepository.saveAll(toSave);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Two retries racing each other. The unique index did its job; nothing is lost.
            log.debug("Duplicate violation batch from user {} on contest {}", userId, contestId);
            return 0;
        }
        return toSave.size();
    }

    /**
     * Keeps a reported timestamp inside the contest window.
     *
     * The clock belongs to the client, and a client that is wrong — or lying — should not be
     * able to place an event outside the round it belongs to. Anything wildly outside the
     * window is dropped; anything close is pulled to the boundary rather than discarded, since
     * a few seconds of clock skew is ordinary.
     */
    private Instant clampToContest(Instant reported, GroupContest contest, Instant now) {
        Instant when = reported == null ? now : reported;

        Instant start = contest.getStartsAt();
        Instant end = contest.getEndsAt();
        if (start == null || end == null) return when;

        Instant earliest = start.minusSeconds(PRE_START_TOLERANCE_MINUTES * 60);
        Instant latest = end.plusSeconds(GRACE_MINUTES * 60);

        if (when.isBefore(earliest) || when.isAfter(latest)) return null;
        if (when.isBefore(start)) return start;
        if (when.isAfter(end)) return end;
        return when;
    }

    // ------------------------------------------------------------ admin reads

    public GroupsDto.ViolationFeed feed(GroupContest contest, int limit) {
        List<ContestViolation> rows = violationRepository.findByContest(
            contest.getContestId(), PageRequest.of(0, Math.clamp(limit, 1, 500)));

        List<GroupsDto.ViolationEntry> entries = rows.stream()
            .map(row -> new GroupsDto.ViolationEntry(
                row.getViolationId(),
                row.getUser().getUserId(),
                row.getUser().getUsername(),
                row.getType(),
                row.getDetail(),
                row.getDurationMs(),
                row.getOccurredAt(),
                row.getReportedAt()))
            .toList();

        return new GroupsDto.ViolationFeed(
            GroupMapper.toContestSummary(contest),
            entries,
            violationRepository.countByContestContestId(contest.getContestId()));
    }

    /**
     * Per-member totals for the standings table, as one grouped query.
     *
     * Deliberately several numbers rather than one. There is no honest way to weigh a clipboard
     * wipe against ninety seconds away from the window, and producing a single figure would
     * only hide that from whoever reads it.
     */
    public Map<Long, GroupsDto.ViolationSummary> summarise(Long contestId) {
        Map<Long, int[]> counts = new HashMap<>();     // [focus, clipboard, blocked, flags, total]
        Map<Long, Long> awayByUser = new HashMap<>();

        for (Object[] row : violationRepository.summariseByMember(contestId)) {
            Long userId = (Long) row[0];
            String type = (String) row[1];
            int count = ((Number) row[2]).intValue();
            long duration = ((Number) row[3]).longValue();

            int[] tally = counts.computeIfAbsent(userId, k -> new int[5]);
            tally[4] += count;

            switch (ContestViolation.Type.valueOf(type)) {
                case FOCUS_LOST -> {
                    tally[0] += count;
                    awayByUser.merge(userId, duration, Long::sum);
                }
                case CLIPBOARD_FOREIGN -> tally[1] += count;
                case BLOCKED_ACTION -> tally[2] += count;
                case LOCKDOWN_UNAVAILABLE -> tally[3] |= 1;
                case LOCKDOWN_PARTIAL -> tally[3] |= 2;
                case LOCKDOWN_RELEASED -> tally[3] |= 4;
            }
        }

        Map<Long, GroupsDto.ViolationSummary> summaries = new HashMap<>();
        counts.forEach((userId, tally) -> summaries.put(userId, new GroupsDto.ViolationSummary(
            tally[0],
            awayByUser.getOrDefault(userId, 0L),
            tally[1],
            tally[2],
            (tally[3] & 1) != 0,
            (tally[3] & 2) != 0,
            (tally[3] & 4) != 0,
            tally[4])));
        return summaries;
    }
}
