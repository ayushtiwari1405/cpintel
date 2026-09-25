package com.cpintel.repository.jpa;

import com.cpintel.entity.AuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>,
                                            JpaSpecificationExecutor<AuditLog> {

    List<AuditLog> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<AuditLog> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    List<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Every row of one action against one entity, oldest first — e.g. sign-ins to one exam. */
    List<AuditLog> findByActionAndEntityTypeAndEntityIdOrderByCreatedAtAsc(
        String action, String entityType, String entityId);

    /**
     * The actions actually present in the trail, for the filter list.
     *
     * Read from the data rather than from the constants on AuditService, so an action recorded
     * by an older version of the code still appears as something you can filter by.
     */
    @Query("SELECT DISTINCT a.action FROM AuditLog a ORDER BY a.action")
    List<String> distinctActions();

    /**
     * Last sign-in for a page of users, as one query rather than one per row. Returns
     * {userId, timestamp} pairs; users who have never signed in are simply absent.
     */
    @Query("""
        SELECT a.userId, MAX(a.createdAt) FROM AuditLog a
        WHERE a.action = 'LOGIN' AND a.userId IN :userIds
        GROUP BY a.userId
        """)
    List<Object[]> lastLoginFor(@Param("userIds") Collection<Long> userIds);
}
