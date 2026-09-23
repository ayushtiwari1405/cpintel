package com.cpintel.repository.jpa;

import com.cpintel.entity.ExamEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ExamEventRepository
        extends JpaRepository<ExamEvent, Long>, JpaSpecificationExecutor<ExamEvent> {

    boolean existsByContestContestIdAndUserUserIdAndEventId(
        Long contestId, Long userId, String eventId);

    /*
     * The filtered log is a Specification rather than a query with a pile of null checks.
     *
     * The obvious JPQL — `(:type IS NULL OR e.type = :type)` repeated per filter — is what was
     * here first, and PostgreSQL refuses it: a bare parameter compared to NULL has no inferrable
     * type, and the driver answers "could not determine data type of parameter $4" at runtime
     * rather than at startup. Building the predicates that are actually wanted avoids both the
     * cast noise and one statement per combination of filters. See ExamEventService#search.
     */

    /**
     * Per-candidate totals for the monitoring dashboard, as one grouped query.
     *
     * Returns [userId, type, count, summed duration]. The dashboard is polled every few seconds
     * by every invigilator watching, so the alternative — reading the whole log and counting in
     * Java — would grow with the length of the examination rather than with the number of
     * people sitting it.
     */
    @Query("""
        SELECT e.user.userId, e.type, COUNT(e), COALESCE(SUM(e.durationMs), 0)
        FROM ExamEvent e
        WHERE e.contest.contestId = :contestId
        GROUP BY e.user.userId, e.type
        """)
    List<Object[]> summariseByUser(@Param("contestId") Long contestId);

    /** The newest event per candidate: what they were last seen doing, and when. */
    @Query("""
        SELECT e FROM ExamEvent e
        WHERE e.contest.contestId = :contestId
          AND e.occurredAt = (
            SELECT MAX(e2.occurredAt) FROM ExamEvent e2
            WHERE e2.contest.contestId = :contestId AND e2.user.userId = e.user.userId)
        """)
    List<ExamEvent> latestPerUser(@Param("contestId") Long contestId);

    List<ExamEvent> findByContestContestIdAndUserUserIdOrderByOccurredAtDesc(
        Long contestId, Long userId, Pageable pageable);

    long countByContestContestId(Long contestId);

    /**
     * How much examination history one account carries.
     *
     * Asked before an account is deleted, because the cascade would take this with it — and a
     * session log is evidence about a paper somebody may still be marking or appealing. The
     * answer is only ever compared against zero.
     */
    long countByUserUserId(Long userId);

    /**
     * Drops events older than the configured retention window.
     *
     * By {@code recordedAt} rather than {@code occurredAt}: the first is CPIntel's own clock and
     * the second is the client's, and a candidate whose machine thinks it is 2019 should not be
     * able to have their session swept away early.
     */
    @Modifying
    @Query("DELETE FROM ExamEvent e WHERE e.recordedAt < :cutoff")
    int deleteRecordedBefore(@Param("cutoff") Instant cutoff);
}
