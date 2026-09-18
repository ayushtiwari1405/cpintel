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

    /**
     * The contest this person is sitting right now, if any.
     *
     * Matched on the external contest rather than on a CPIntel id, because the compete page
     * knows only which Codeforces round is open — it has no idea a group was laid over it.
     * Membership is part of the query so that opening someone else's group contest finds
     * nothing rather than reporting into it.
     */
    @Query("""
        SELECT c FROM GroupContest c JOIN c.group g JOIN g.members m
        WHERE m.user.userId = :userId
          AND c.platform = :platform
          AND c.externalId = :externalId
          AND g.isActive = true
        ORDER BY c.startsAt DESC
        """)
    List<GroupContest> findForParticipant(@Param("userId") Long userId,
                                          @Param("platform") String platform,
                                          @Param("externalId") String externalId);

    /** Everything this person is enrolled in, for their own list. */
    @Query("""
        SELECT c FROM GroupContest c JOIN c.group g JOIN g.members m
        WHERE m.user.userId = :userId AND g.isActive = true
        ORDER BY c.startsAt DESC
        """)
    List<GroupContest> findAllForParticipant(@Param("userId") Long userId);

    /** Contests whose window is open, which are the ones worth refreshing often. */
    @Query("""
        SELECT c FROM GroupContest c
        WHERE c.startsAt <= :now AND c.endsAt >= :now
        """)
    List<GroupContest> findLive(@Param("now") Instant now);
}
