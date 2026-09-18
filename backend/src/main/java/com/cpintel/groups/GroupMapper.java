package com.cpintel.groups;

import com.cpintel.entity.ContestGroup;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.GroupMember;
import com.cpintel.entity.GroupStanding;

import java.time.Instant;
import java.util.List;

/**
 * Entity to DTO, kept static and free of dependencies so both the admin and participant paths
 * produce identical shapes from the same rows.
 */
public final class GroupMapper {

    private GroupMapper() {}

    public static GroupsDto.GroupSummary toGroupSummary(ContestGroup group, int members, int contests) {
        return new GroupsDto.GroupSummary(
            group.getGroupId(),
            group.getName(),
            group.getDescription(),
            Boolean.TRUE.equals(group.getIsActive()),
            members,
            contests,
            group.getCreatedAt());
    }

    public static GroupsDto.Member toMember(GroupMember member, String codeforcesHandle) {
        var user = member.getUser();
        return new GroupsDto.Member(
            user.getUserId(),
            user.getUsername(),
            user.getEmail(),
            user.getFullName(),
            codeforcesHandle,
            member.getExternalHandle(),
            Boolean.TRUE.equals(user.getIsActive()),
            member.getJoinedAt());
    }

    public static GroupsDto.ContestSummary toContestSummary(GroupContest contest) {
        return new GroupsDto.ContestSummary(
            contest.getContestId(),
            contest.getGroup().getGroupId(),
            contest.getGroup().getName(),
            contest.getPlatform(),
            contest.getExternalId(),
            contest.getName(),
            contest.getUrl(),
            contest.getStartsAt(),
            contest.getEndsAt(),
            Boolean.TRUE.equals(contest.getLockdownRequired()),
            statusOf(contest, Instant.now()),
            contest.getStandingsRefreshedAt(),
            contest.getStandingsError());
    }

    /**
     * Derived from the window rather than stored.
     *
     * A stored status would need something to keep it up to date, and would be wrong for
     * exactly as long as that something was down — during the contest, which is the only time
     * anybody is looking.
     */
    public static String statusOf(GroupContest contest, Instant now) {
        if (contest.getStartsAt() == null || contest.getEndsAt() == null) return "SCHEDULED";
        if (now.isBefore(contest.getStartsAt())) return "SCHEDULED";
        return now.isBefore(contest.getEndsAt()) ? "LIVE" : "FINISHED";
    }

    public static GroupsDto.StandingRow toStandingRow(GroupStanding row,
                                                      GroupsDto.ViolationSummary violations) {
        return new GroupsDto.StandingRow(
            row.getGroupRank(),
            row.getUser().getUserId(),
            row.getUser().getUsername(),
            row.getHandle(),
            row.getSolved(),
            row.getPenalty(),
            row.getScore() == null ? null : row.getScore().doubleValue(),
            row.getDetail(),
            Boolean.TRUE.equals(row.getFound()),
            violations);
    }

    public static List<String> unmatched(List<GroupStanding> rows) {
        return rows.stream()
            .filter(row -> !Boolean.TRUE.equals(row.getFound()))
            .map(row -> row.getUser().getUsername())
            .toList();
    }
}
