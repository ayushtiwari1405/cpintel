package com.cpintel.repository.jpa;

import com.cpintel.entity.RevisionSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RevisionScheduleRepository extends JpaRepository<RevisionSchedule, Long> {
    List<RevisionSchedule> findByUserUserId(Long userId);
    Optional<RevisionSchedule> findByUserUserIdAndTopic(Long userId, String topic);

    @Query(value = """
        SELECT rs.* FROM revision_schedule rs
        WHERE rs.user_id = :userId
        AND rs.next_revision_at <= SYSTIMESTAMP
        ORDER BY rs.revision_priority DESC, rs.next_revision_at ASC
        """, nativeQuery = true)
    List<RevisionSchedule> findDueRevisions(@Param("userId") Long userId);
}
