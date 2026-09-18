package com.cpintel.repository.jpa;

import com.cpintel.entity.ContestViolation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ContestViolationRepository extends JpaRepository<ContestViolation, Long> {

    @Query("""
        SELECT v FROM ContestViolation v JOIN FETCH v.user
        WHERE v.contest.contestId = :contestId
        ORDER BY v.occurredAt DESC
        """)
    List<ContestViolation> findByContest(@Param("contestId") Long contestId, Pageable pageable);

    boolean existsByContestContestIdAndUserUserIdAndEventId(
        Long contestId, Long userId, String eventId);

    long countByContestContestId(Long contestId);

    /** Per-member totals for the standings table, as one query rather than one per row. */
    @Query("""
        SELECT v.user.userId, v.type, COUNT(v), COALESCE(SUM(v.durationMs), 0)
        FROM ContestViolation v
        WHERE v.contest.contestId = :contestId
        GROUP BY v.user.userId, v.type
        """)
    List<Object[]> summariseByMember(@Param("contestId") Long contestId);
}
