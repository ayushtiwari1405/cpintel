package com.cpintel.repository.jpa;

import com.cpintel.entity.SyncJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface SyncJobRepository extends JpaRepository<SyncJob, Long> {
    List<SyncJob> findByUserUserIdOrderByCreatedAtDesc(Long userId);

    @Query(value = """
        SELECT sj.* FROM sync_jobs sj
        WHERE sj.user_id = :userId AND sj.platform = :platform
        ORDER BY sj.created_at DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<SyncJob> findLatestByUserAndPlatform(
        @Param("userId") Long userId,
        @Param("platform") String platform
    );

    @Query("SELECT sj FROM SyncJob sj WHERE sj.status IN ('QUEUED','RUNNING') ORDER BY sj.createdAt ASC")
    List<SyncJob> findPendingJobs();

    // ------------------------------------------------------ admin console counts

    long countByStatus(String status);

    /**
     * Marks a job failed in a transaction of its own. Used after the transaction that created
     * the job has committed — joining that one would write into a transaction already done.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE SyncJob sj SET sj.status = 'FAILED', sj.errorMsg = :message, "
        + "sj.completedAt = :at WHERE sj.jobId = :id")
    int markFailed(@Param("id") Long id, @Param("message") String message,
                   @Param("at") Instant at);

    /**
     * Recently finished jobs of one outcome. The admin overview cares about the last day, not
     * about every failure since the deployment started — an old failure that has since been
     * retried successfully is noise on a status screen.
     */
    long countByStatusAndCompletedAtAfter(String status, Instant since);
}
