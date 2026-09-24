package com.cpintel.repository.jpa;

import com.cpintel.entity.GroupContest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface GroupContestRepository extends JpaRepository<GroupContest, Long> {

    List<GroupContest> findByGroupGroupIdOrderByStartsAtDesc(Long groupId);

    Optional<GroupContest> findByGroupGroupIdAndPlatformAndExternalId(
        Long groupId, String platform, String externalId);

    List<GroupContest> findByKindOrderByStartsAtDesc(String kind);

    /** Every event run on one judge contest — usually one, but nothing forbids reuse. */
    List<GroupContest> findByPlatformAndExternalId(String platform, String externalId);

    /**
     * Everything one person may enter, whichever way they were given it.
     *
     * Three routes, unioned: their team was assigned, they were named directly, or the event is
     * public. Written as a union of three selects rather than as one join with a pile of ORs
     * because each route has its own index and the planner can use all three; more importantly
     * the three are genuinely different arrangements, and a single condition that happened to
     * cover them would be one edit away from silently covering a fourth.
     *
     * <p>Drafts are excluded here rather than at the call site. A draft is an event nobody has
     * finished writing, and the one thing that must never happen is a half-configured
     * examination appearing on a candidate's screen because it was saved with today's date on
     * it.
     */
    @Query("""
        SELECT c FROM GroupContest c WHERE c.lifecycle <> 'DRAFT' AND (
            EXISTS (SELECT 1 FROM ContestAssignment a JOIN a.group g JOIN g.members m
                    WHERE a.contest = c AND m.user.userId = :userId AND g.isActive = true)
         OR EXISTS (SELECT 1 FROM ContestAssignment a
                    WHERE a.contest = c AND a.user.userId = :userId)
         OR c.visibility = 'PUBLIC')
        ORDER BY c.startsAt DESC
        """)
    List<GroupContest> findAllForParticipant(@Param("userId") Long userId);

    /**
     * The event this person is sitting right now, if any, matched on the external contest.
     *
     * The compete page knows only which round is open on the judge — it has no idea CPIntel laid
     * an event over it — so this is how the monitor learns where to report. The participation
     * check is part of the query so that opening somebody else's examination finds nothing
     * rather than reporting into it.
     */
    @Query("""
        SELECT c FROM GroupContest c
        WHERE c.platform = :platform AND c.externalId = :externalId
          AND c.lifecycle <> 'DRAFT' AND (
            EXISTS (SELECT 1 FROM ContestAssignment a JOIN a.group g JOIN g.members m
                    WHERE a.contest = c AND m.user.userId = :userId AND g.isActive = true)
         OR EXISTS (SELECT 1 FROM ContestAssignment a
                    WHERE a.contest = c AND a.user.userId = :userId)
         OR c.visibility = 'PUBLIC')
        ORDER BY c.startsAt DESC
        """)
    List<GroupContest> findForParticipant(@Param("userId") Long userId,
                                          @Param("platform") String platform,
                                          @Param("externalId") String externalId);

    /** Whether this one event is open to this one person. */
    @Query("""
        SELECT COUNT(c) > 0 FROM GroupContest c
        WHERE c.contestId = :contestId AND c.lifecycle <> 'DRAFT' AND (
            EXISTS (SELECT 1 FROM ContestAssignment a JOIN a.group g JOIN g.members m
                    WHERE a.contest = c AND m.user.userId = :userId AND g.isActive = true)
         OR EXISTS (SELECT 1 FROM ContestAssignment a
                    WHERE a.contest = c AND a.user.userId = :userId)
         OR c.visibility = 'PUBLIC')
        """)
    boolean isAssignedTo(@Param("contestId") Long contestId, @Param("userId") Long userId);

    /** Contests whose window is open, which are the ones worth refreshing often. */
    @Query("""
        SELECT c FROM GroupContest c
        WHERE c.startsAt <= :now AND c.endsAt >= :now
        """)
    List<GroupContest> findLive(@Param("now") Instant now);
}
