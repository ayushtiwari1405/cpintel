package com.cpintel.events;

import com.cpintel.entity.ContestProblem;
import com.cpintel.entity.GroupContest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Rows to shapes, kept static so the admin path and the candidate path cannot produce different
 * answers about the same event.
 */
@Slf4j
public final class EventMapper {

    private EventMapper() {}

    /**
     * Counts are passed in rather than read here.
     *
     * A mapper that queried would turn a list of forty examinations into a hundred and sixty
     * queries, and the caller usually has the numbers already — the list screen counts them in
     * one grouped query, the detail screen has the rows in hand.
     */
    public static EventsDto.EventSummary toSummary(GroupContest event, Instant now,
                                                   int assignedTeams, int assignedUsers,
                                                   int participants, int problemCount) {
        Long duration = event.getStartsAt() == null || event.getEndsAt() == null
            ? null
            : event.getEndsAt().getEpochSecond() - event.getStartsAt().getEpochSecond();

        return new EventsDto.EventSummary(
            event.getContestId(),
            event.getKind(),
            event.getPlatform(),
            event.getExternalId(),
            event.getName(),
            event.getDescription(),
            event.getUrl(),
            event.getStartsAt(),
            event.getEndsAt(),
            duration,
            event.effectiveLifecycle(now).name(),
            event.getVisibility(),
            Boolean.TRUE.equals(event.getLockdownRequired()),
            event.getAwayThresholdSeconds() == null ? 10 : event.getAwayThresholdSeconds(),
            event.getGroup() == null ? null : event.getGroup().getGroupId(),
            event.getGroup() == null ? null : event.getGroup().getName(),
            assignedTeams,
            assignedUsers,
            participants,
            problemCount,
            event.getStandingsRefreshedAt(),
            event.getStandingsError());
    }

    public static EventsDto.ProblemRow toProblem(ContestProblem problem) {
        return new EventsDto.ProblemRow(
            problem.getProblemId(),
            problem.getLabel(),
            problem.getTitle(),
            problem.getExternalId(),
            problem.getOrdering() == null ? 0 : problem.getOrdering(),
            problem.getPoints() == null ? null : problem.getPoints().doubleValue());
    }

    /**
     * The stored desktop policy, or the default for this kind of event.
     *
     * A policy that cannot be read falls back to the default rather than to nothing. The
     * failure mode of "nothing" is an examination that quietly stops restricting anything,
     * which is the one outcome nobody would notice until afterwards.
     */
    public static EventsDto.DesktopPolicy policyOf(GroupContest event, ObjectMapper json) {
        EventsDto.DesktopPolicy fallback = event.isExam()
            ? EventsDto.DesktopPolicy.examDefault()
            : EventsDto.DesktopPolicy.contestDefault();

        String stored = event.getDesktopPolicy();
        if (stored == null || stored.isBlank()) return fallback;
        try {
            return json.readValue(stored, EventsDto.DesktopPolicy.class)
                .withDefaults(event.isExam());
        } catch (Exception e) {
            log.warn("Unreadable desktop policy on event {}: {}",
                event.getContestId(), e.getMessage());
            return fallback;
        }
    }

    /** Stored comma-separated, because it is a short list the admin typed, not a document. */
    public static List<String> languagesOf(GroupContest event) {
        String stored = event.getAllowedLanguages();
        if (stored == null || stored.isBlank()) return List.of();
        return Arrays.stream(stored.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }
}
