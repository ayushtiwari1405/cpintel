package com.cpintel.groups;

import com.cpintel.entity.ContestGroup;
import com.cpintel.entity.GroupContest;
import com.cpintel.entity.GroupMember;
import com.cpintel.entity.GroupStanding;
import com.cpintel.entity.User;
import com.cpintel.exception.ApiException;
import com.cpintel.repository.jpa.*;
import com.cpintel.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Groups, their membership, and the contests laid over them.
 *
 * A group is the durable object; contests come and go beneath it. That shape is what makes the
 * feature worth having — a class or a training squad is the same set of people across every
 * round they sit, and re-entering thirty names per contest would guarantee nobody used it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GroupService {

    private final ContestGroupRepository groupRepository;
    private final GroupMemberRepository memberRepository;
    private final GroupContestRepository contestRepository;
    private final GroupStandingRepository standingRepository;
    private final PlatformAccountRepository platformAccountRepository;
    private final UserRepository userRepository;
    private final StandingsService standingsService;
    private final ViolationService violationService;
    private final AuditService auditService;

    // ----------------------------------------------------------------- groups

    public List<GroupsDto.GroupSummary> list() {
        return groupRepository.findAllByOrderByCreatedAtDesc().stream()
            .map(group -> GroupMapper.toGroupSummary(group,
                (int) memberRepository.countByGroupGroupId(group.getGroupId()),
                contestRepository.findByGroupGroupIdOrderByStartsAtDesc(group.getGroupId()).size()))
            .toList();
    }

    public GroupsDto.GroupDetail detail(Long groupId) {
        ContestGroup group = require(groupId);
        List<GroupMember> members = memberRepository.findByGroup(groupId);
        List<GroupContest> contests = contestRepository.findByGroupGroupIdOrderByStartsAtDesc(groupId);

        return new GroupsDto.GroupDetail(
            GroupMapper.toGroupSummary(group, members.size(), contests.size()),
            members.stream().map(m -> GroupMapper.toMember(m, codeforcesHandle(m))).toList(),
            contests.stream().map(GroupMapper::toContestSummary).toList());
    }

    @Transactional
    public GroupsDto.GroupSummary create(Long adminId, GroupsDto.GroupRequest req,
                                         HttpServletRequest httpReq) {
        String name = req.name().trim();
        if (groupRepository.existsByNameIgnoreCase(name)) {
            throw ApiException.conflict("A group called \"" + name + "\" already exists.");
        }

        ContestGroup group = groupRepository.save(ContestGroup.builder()
            .name(name)
            .description(trimToNull(req.description()))
            .ownerId(adminId)
            .isActive(true)
            .build());

        auditService.record(adminId, AuditService.GROUP_CREATED, "GROUP",
            group.getGroupId() + ":" + name, httpReq);
        return GroupMapper.toGroupSummary(group, 0, 0);
    }

    @Transactional
    public GroupsDto.GroupSummary update(Long adminId, Long groupId, GroupsDto.GroupRequest req,
                                         HttpServletRequest httpReq) {
        ContestGroup group = require(groupId);
        group.setName(req.name().trim());
        group.setDescription(trimToNull(req.description()));
        groupRepository.save(group);

        auditService.record(adminId, AuditService.GROUP_UPDATED, "GROUP",
            String.valueOf(groupId), httpReq);
        return GroupMapper.toGroupSummary(group,
            (int) memberRepository.countByGroupGroupId(groupId),
            contestRepository.findByGroupGroupIdOrderByStartsAtDesc(groupId).size());
    }

    /**
     * Retires a group without deleting it.
     *
     * Deleting would cascade through every contest, standings snapshot and violation the group
     * ever produced. Those are the record of rounds that actually happened, and no confirmation
     * dialog makes destroying them recoverable.
     */
    @Transactional
    public void deactivate(Long adminId, Long groupId, HttpServletRequest httpReq) {
        ContestGroup group = require(groupId);
        group.setIsActive(false);
        groupRepository.save(group);
        auditService.record(adminId, AuditService.GROUP_DEACTIVATED, "GROUP",
            String.valueOf(groupId), httpReq);
    }

    // ---------------------------------------------------------------- members

    @Transactional
    public GroupsDto.Member addMember(Long adminId, Long groupId, GroupsDto.MemberRequest req,
                                      HttpServletRequest httpReq) {
        ContestGroup group = require(groupId);
        User user = userRepository.findById(req.userId())
            .orElseThrow(() -> ApiException.notFound("No such user"));

        if (memberRepository.existsByGroupGroupIdAndUserUserId(groupId, req.userId())) {
            throw ApiException.conflict(user.getUsername() + " is already in this group.");
        }

        GroupMember member = memberRepository.save(GroupMember.builder()
            .group(group)
            .user(user)
            .externalHandle(trimToNull(req.externalHandle()))
            .joinedAt(Instant.now())
            .build());

        auditService.record(adminId, AuditService.GROUP_MEMBER_ADDED, "GROUP",
            groupId + ":" + req.userId(), httpReq);
        return GroupMapper.toMember(member, codeforcesHandle(member));
    }

    @Transactional
    public GroupsDto.Member updateMember(Long adminId, Long groupId, Long userId,
                                         GroupsDto.MemberRequest req, HttpServletRequest httpReq) {
        GroupMember member = memberRepository.findByGroupGroupIdAndUserUserId(groupId, userId)
            .orElseThrow(() -> ApiException.notFound("That person is not in this group"));

        member.setExternalHandle(trimToNull(req.externalHandle()));
        memberRepository.save(member);

        auditService.record(adminId, AuditService.GROUP_MEMBER_UPDATED, "GROUP",
            groupId + ":" + userId, httpReq);
        return GroupMapper.toMember(member, codeforcesHandle(member));
    }

    @Transactional
    public void removeMember(Long adminId, Long groupId, Long userId, HttpServletRequest httpReq) {
        if (!memberRepository.existsByGroupGroupIdAndUserUserId(groupId, userId)) {
            throw ApiException.notFound("That person is not in this group");
        }
        memberRepository.deleteByGroupGroupIdAndUserUserId(groupId, userId);
        auditService.record(adminId, AuditService.GROUP_MEMBER_REMOVED, "GROUP",
            groupId + ":" + userId, httpReq);
    }

    // --------------------------------------------------------------- contests

    @Transactional
    public GroupsDto.ContestSummary addContest(Long adminId, Long groupId,
                                               GroupsDto.ContestRequest req,
                                               HttpServletRequest httpReq) {
        ContestGroup group = require(groupId);
        String platform = platformOf(req.platform());
        String externalId = req.externalId().trim();

        contestRepository.findByGroupGroupIdAndPlatformAndExternalId(groupId, platform, externalId)
            .ifPresent(existing -> {
                throw ApiException.conflict(
                    "This group already has " + platform + " contest " + externalId + ".");
            });

        if (req.startsAt() != null && req.endsAt() != null && !req.endsAt().isAfter(req.startsAt())) {
            throw ApiException.badRequest("The contest must end after it starts.");
        }

        GroupContest contest = contestRepository.save(GroupContest.builder()
            .group(group)
            .platform(platform)
            .externalId(externalId)
            .name(req.name().trim())
            .url(trimToNull(req.url()))
            .startsAt(req.startsAt())
            .endsAt(req.endsAt())
            .lockdownRequired(req.lockdownRequired() == null || req.lockdownRequired())
            .build());

        auditService.record(adminId, AuditService.GROUP_CONTEST_ADDED, "CONTEST",
            groupId + ":" + platform + ":" + externalId, httpReq);
        return GroupMapper.toContestSummary(contest);
    }

    @Transactional
    public void removeContest(Long adminId, Long contestId, HttpServletRequest httpReq) {
        GroupContest contest = requireContest(contestId);
        auditService.record(adminId, AuditService.GROUP_CONTEST_REMOVED, "CONTEST",
            contestId + ":" + contest.getPlatform() + ":" + contest.getExternalId(), httpReq);
        contestRepository.delete(contest);
    }

    // -------------------------------------------------------------- standings

    /** Rebuilds the board now, rather than waiting for the scheduler. */
    @Transactional
    public GroupsDto.Standings refreshStandings(Long contestId) {
        GroupContest contest = requireContest(contestId);
        standingsService.refresh(contest);
        contestRepository.save(contest);
        return standings(contestId, true);
    }

    /**
     * The cached board.
     *
     * `withViolations` is what separates the admin's view from a participant's. It is a
     * parameter rather than a field on the row so that the participant path cannot accidentally
     * carry conduct data about other people by forgetting to null something out.
     */
    public GroupsDto.Standings standings(Long contestId, boolean withViolations) {
        GroupContest contest = requireContest(contestId);
        List<GroupStanding> rows = standingRepository.findByContest(contestId);

        Map<Long, GroupsDto.ViolationSummary> violations = withViolations
            ? violationService.summarise(contestId)
            : Map.of();

        List<GroupsDto.StandingRow> mapped = new ArrayList<>(rows.size());
        for (GroupStanding row : rows) {
            mapped.add(GroupMapper.toStandingRow(row,
                violations.get(row.getUser().getUserId())));
        }

        return new GroupsDto.Standings(
            GroupMapper.toContestSummary(contest),
            mapped,
            contest.getStandingsRefreshedAt(),
            contest.getStandingsError(),
            GroupMapper.unmatched(rows));
    }

    public GroupsDto.ViolationFeed violations(Long contestId, int limit) {
        return violationService.feed(requireContest(contestId), limit);
    }

    // ------------------------------------------------------ participant reads

    /** The groups this person is in, for their own screen. */
    public List<GroupsDto.GroupSummary> myGroups(Long userId) {
        return groupRepository.findForMember(userId).stream()
            .map(group -> GroupMapper.toGroupSummary(group,
                (int) memberRepository.countByGroupGroupId(group.getGroupId()),
                contestRepository.findByGroupGroupIdOrderByStartsAtDesc(group.getGroupId()).size()))
            .toList();
    }

    /**
     * The contests this person is enrolled in, with their own position and nothing else.
     *
     * Deliberately not the whole board. Whether a group publishes its internal ranking to its
     * members is the admin's decision to make, and defaulting to "everyone sees everyone" would
     * make that decision for them.
     */
    public List<GroupsDto.MyContest> myContests(Long userId) {
        List<GroupsDto.MyContest> result = new ArrayList<>();
        for (GroupContest contest : contestRepository.findAllForParticipant(userId)) {
            List<GroupStanding> rows = standingRepository.findByContest(contest.getContestId());
            GroupStanding mine = rows.stream()
                .filter(row -> row.getUser().getUserId().equals(userId))
                .findFirst().orElse(null);

            result.add(new GroupsDto.MyContest(
                GroupMapper.toContestSummary(contest),
                mine == null ? null : mine.getGroupRank(),
                rows.isEmpty() ? null : rows.size(),
                mine == null ? null : mine.getSolved()));
        }
        return result;
    }

    /**
     * The group contest a participant is sitting right now, matched on the external contest.
     *
     * The compete page knows only which Codeforces round is open — it has no idea a group was
     * laid over it — so this is how the lock learns where to report. Returns null when there is
     * nothing to report to, which is the normal case for ordinary practice.
     */
    public GroupsDto.ContestSummary activeFor(Long userId, String platform, String externalId) {
        List<GroupContest> candidates = contestRepository.findForParticipant(
            userId, platformOf(platform), externalId.trim());
        if (candidates.isEmpty()) return null;

        Instant now = Instant.now();
        // A live round wins over a finished one, which matters when the same contest has been
        // used by a group twice — a re-run for people who missed it, say.
        return candidates.stream()
            .filter(contest -> contest.isLive(now))
            .findFirst()
            .map(GroupMapper::toContestSummary)
            .orElseGet(() -> GroupMapper.toContestSummary(candidates.get(0)));
    }

    // ---------------------------------------------------------------- helpers

    private ContestGroup require(Long groupId) {
        return groupRepository.findById(groupId)
            .orElseThrow(() -> ApiException.notFound("No such group"));
    }

    GroupContest requireContest(Long contestId) {
        return contestRepository.findById(contestId)
            .orElseThrow(() -> ApiException.notFound("No such contest"));
    }

    private String platformOf(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        try {
            return GroupContest.Platform.valueOf(value).name();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Platform must be CODEFORCES or DOMJUDGE");
        }
    }

    private String codeforcesHandle(GroupMember member) {
        return platformAccountRepository
            .findByUserUserIdAndPlatform(member.getUser().getUserId(), "CODEFORCES")
            .map(account -> account.getHandle())
            .orElse(null);
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /** Cached counts for the admin overview card. */
    public Map<String, Long> counts() {
        Map<String, Long> counts = new HashMap<>();
        counts.put("groups", groupRepository.count());
        counts.put("contests", contestRepository.count());
        return counts;
    }
}
