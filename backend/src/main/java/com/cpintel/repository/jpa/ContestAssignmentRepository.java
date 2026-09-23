package com.cpintel.repository.jpa;

import com.cpintel.entity.ContestAssignment;
import com.cpintel.entity.GroupContest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ContestAssignmentRepository extends JpaRepository<ContestAssignment, Long> {

    @Query("""
        SELECT a FROM ContestAssignment a
        LEFT JOIN FETCH a.group
        LEFT JOIN FETCH a.user
        WHERE a.contest.contestId = :contestId
        ORDER BY a.assignedAt
        """)
    List<ContestAssignment> findByContest(@Param("contestId") Long contestId);

    boolean existsByContestContestIdAndGroupGroupId(Long contestId, Long groupId);

    boolean existsByContestContestIdAndUserUserId(Long contestId, Long userId);

    void deleteByContestContestIdAndGroupGroupId(Long contestId, Long groupId);

    void deleteByContestContestIdAndUserUserId(Long contestId, Long userId);

    /**
     * Everyone who may enter, as user ids, with teams already expanded.
     *
     * One query rather than a fetch per assignment: an examination assigned to three classes is
     * three rows here and ninety people, and the monitoring dashboard asks for this list every
     * few seconds while the examination is running.
     */
    @Query("""
        SELECT DISTINCT m.user.userId FROM ContestAssignment a
        JOIN a.group g JOIN g.members m
        WHERE a.contest.contestId = :contestId AND g.isActive = true
        """)
    List<Long> participantsByTeam(@Param("contestId") Long contestId);

    @Query("""
        SELECT a.user.userId FROM ContestAssignment a
        WHERE a.contest.contestId = :contestId AND a.user IS NOT NULL
        """)
    List<Long> participantsNamedDirectly(@Param("contestId") Long contestId);

    /** Every event a team was assigned to, for the team's own analytics. */
    @Query("""
        SELECT a.contest FROM ContestAssignment a
        WHERE a.group.groupId = :teamId
        ORDER BY a.contest.startsAt DESC
        """)
    List<GroupContest> eventsForTeam(@Param("teamId") Long teamId);
}
