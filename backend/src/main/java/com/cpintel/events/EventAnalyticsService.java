package com.cpintel.events;

import com.cpintel.entity.ContestGroup;
import com.cpintel.entity.ExamEvent;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.GroupStanding;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.ContestAssignmentRepository;
import com.cpintel.repository.jpa.ContestGroupRepository;
import com.cpintel.repository.jpa.GroupContestRepository;
import com.cpintel.repository.jpa.GroupMemberRepository;
import com.cpintel.repository.jpa.GroupStandingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * How people and teams have done across the events they were given.
 *
 * <p>Built from what already exists rather than from a new tally: placings come from the cached
 * standings snapshot, participation from the examination log. Nothing here is maintained
 * incrementally, which is the point — a counter that has to be kept in step with events is a
 * counter that is wrong after the first failed write, and these numbers are read far less often
 * than they would have to be updated.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventAnalyticsService {

    /** How many recent events a team's page lists. Enough to see a trend, not a year of rows. */
    private static final int RECENT_EVENTS = 12;

    private final GroupContestRepository eventRepository;
    private final GroupStandingRepository standingRepository;
    private final ContestGroupRepository groupRepository;
    private final GroupMemberRepository memberRepository;
    private final ContestAssignmentRepository assignmentRepository;
    private final ExamEventService examEvents;
    private final EventService events;

    /**
     * One person's history with everything they were assigned.
     *
     * Includes events they never entered, which is the part an admin usually came for: a
     * candidate who did not sit an examination is invisible in any list built from results.
     */
    @Transactional(readOnly = true)
    public List<EventsDto.ParticipationRow> participation(Long userId) {
        Instant now = Instant.now();
        List<EventsDto.ParticipationRow> rows = new ArrayList<>();

        for (GroupContest event : eventRepository.findAllForParticipant(userId)) {
            Long eventId = event.getContestId();

            GroupStanding mine = null;
            int groupSize = 0;
            List<GroupStanding> standings = standingRepository.findByContest(eventId);
            groupSize = standings.size();
            for (GroupStanding row : standings) {
                if (row.getUser().getUserId().equals(userId)) {
                    mine = row;
                    break;
                }
            }

            Map<String, Integer> counts = examEvents.countsFor(eventId, userId);

            rows.add(new EventsDto.ParticipationRow(
                eventId,
                event.getKind(),
                event.getName(),
                event.getPlatform(),
                event.getStartsAt(),
                event.getEndsAt(),
                event.effectiveLifecycle(now).name(),
                mine == null ? null : mine.getGroupRank(),
                groupSize == 0 ? null : groupSize,
                mine == null ? null : mine.getSolved(),
                mine == null ? null : mine.getPenalty(),
                mine == null || mine.getScore() == null ? null : mine.getScore().doubleValue(),
                counts.getOrDefault(ExamEvent.Type.EXAM_ENTERED.name(), 0) > 0,
                counts.getOrDefault(ExamEvent.Type.PROBLEM_SUBMITTED.name(), 0),
                counts.getOrDefault(ExamEvent.Type.FOCUS_LOST.name(), 0)));
        }
        return rows;
    }

    /**
     * How a team has done, across every event it was assigned.
     *
     * <p>{@code participationRate} is the number worth reading first, and it is deliberately
     * members-who-turned-up over members-assigned rather than anything about scores. A team
     * whose average is respectable because a third of it never sat the examination is the
     * situation this page exists to make visible.
     */
    @Transactional(readOnly = true)
    public EventsDto.TeamAnalytics team(Long teamId) {
        ContestGroup team = groupRepository.findById(teamId)
            .orElseThrow(() -> ApiException.notFound("No such team"));

        int memberCount = (int) memberRepository.countByGroupGroupId(teamId);
        List<GroupContest> assigned = eventsFor(teamId);

        int contests = 0;
        int exams = 0;
        int totalSolved = 0;
        int rankedRows = 0;
        double scoreSum = 0;
        int scoredRows = 0;
        java.util.Set<Long> turnedUp = new java.util.HashSet<>();
        java.util.Set<Long> everAssigned = new java.util.HashSet<>();

        List<EventsDto.TeamEventRow> recent = new ArrayList<>();

        for (GroupContest event : assigned) {
            if (event.isExam()) exams++; else contests++;
            Long eventId = event.getContestId();
            everAssigned.addAll(events.participantIds(eventId));

            List<GroupStanding> standings = standingRepository.findByContest(eventId);
            int solvedHere = 0;
            int rowsHere = 0;
            Integer bestRank = null;
            String bestMember = null;

            for (GroupStanding row : standings) {
                rowsHere++;
                rankedRows++;
                solvedHere += row.getSolved();
                totalSolved += row.getSolved();
                if (row.getScore() != null) {
                    scoreSum += row.getScore().doubleValue();
                    scoredRows++;
                }
                if (row.getGroupRank() != null && (bestRank == null || row.getGroupRank() < bestRank)) {
                    bestRank = row.getGroupRank();
                    bestMember = row.getUser().getUsername();
                }
                // A standings row exists because the judge found them on its board, which is
                // the most reliable evidence that somebody actually sat the event.
                turnedUp.add(row.getUser().getUserId());
            }

            // The examination log also proves attendance, and it works for an event whose
            // standings have not been built yet — which is every examination while it is
            // still running.
            for (Map.Entry<Long, Map<String, long[]>> entry
                    : examEvents.summarise(eventId).entrySet()) {
                if (entry.getValue().containsKey(ExamEvent.Type.EXAM_ENTERED.name())) {
                    turnedUp.add(entry.getKey());
                }
            }

            if (recent.size() < RECENT_EVENTS) {
                recent.add(new EventsDto.TeamEventRow(
                    eventId,
                    event.getKind(),
                    event.getName(),
                    event.getStartsAt(),
                    event.effectiveLifecycle(Instant.now()).name(),
                    rowsHere,
                    rowsHere == 0 ? 0 : (double) solvedHere / rowsHere,
                    bestRank,
                    bestMember));
            }
        }

        int participants = (int) turnedUp.stream().filter(everAssigned::contains).count();
        int expected = everAssigned.isEmpty() ? memberCount : everAssigned.size();

        return new EventsDto.TeamAnalytics(
            teamId,
            team.getName(),
            memberCount,
            assigned.size(),
            contests,
            exams,
            participants,
            expected == 0 ? 0 : (double) participants / expected,
            rankedRows == 0 ? 0 : (double) totalSolved / rankedRows,
            scoredRows == 0 ? 0 : scoreSum / scoredRows,
            totalSolved,
            recent);
    }

    /**
     * Every event this team may sit, whether it owns them or was merely assigned to them.
     *
     * Both, because the two are genuinely different: a team owns the events an admin created
     * from its page, and is assigned to the examinations somebody else set for several classes
     * at once. A team's record is the union, and an event that is both must appear once.
     */
    private List<GroupContest> eventsFor(Long teamId) {
        List<GroupContest> assigned = new ArrayList<>(
            eventRepository.findByGroupGroupIdOrderByStartsAtDesc(teamId));
        java.util.Set<Long> seen = new java.util.HashSet<>();
        assigned.forEach(event -> seen.add(event.getContestId()));

        for (GroupContest event : assignmentRepository.eventsForTeam(teamId)) {
            if (seen.add(event.getContestId())) assigned.add(event);
        }
        assigned.sort((a, b) -> {
            Instant left = a.getStartsAt();
            Instant right = b.getStartsAt();
            if (left == null && right == null) return 0;
            if (left == null) return 1;
            if (right == null) return -1;
            return right.compareTo(left);
        });
        return assigned;
    }
}
