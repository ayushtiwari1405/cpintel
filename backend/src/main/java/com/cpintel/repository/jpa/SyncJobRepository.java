package com.cpintel.repository.jpa;

import com.cpintel.entity.SyncJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SyncJobRepository extends JpaRepository<SyncJob, Long> {
    List<SyncJob> findByUserUserIdOrderByCreatedAtDesc(Long userId);

    @Query(value = """
        SELECT sj.* FROM sync_jobs sj
        WHERE sj.user_id = :userId AND sj.platform = :platform
        ORDER BY sj.created_at DESC
        FETCH FIRST 1 ROWS ONLY
        """, nativeQuery = true)
    Optional<SyncJob> findLatestByUserAndPlatform(
        @Param("userId") Long userId,
        @Param("platform") String platform
    );

    @Query("SELECT sj FROM SyncJob sj WHERE sj.status IN ('QUEUED','RUNNING') ORDER BY sj.createdAt ASC")
    List<SyncJob> findPendingJobs();
}
