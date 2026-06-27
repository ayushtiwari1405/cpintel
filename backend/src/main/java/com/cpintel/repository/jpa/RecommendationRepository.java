package com.cpintel.repository.jpa;

import com.cpintel.entity.Recommendation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    @Query(value = """
        SELECT r.* FROM recommendations r
        WHERE r.user_id = :userId
        AND r.rec_type = :type
        AND r.is_consumed = 0
        AND (r.expires_at IS NULL OR r.expires_at > SYSTIMESTAMP)
        ORDER BY r.generated_at DESC
        FETCH FIRST 1 ROWS ONLY
        """, nativeQuery = true)
    Optional<Recommendation> findLatestActiveByUserAndType(
        @Param("userId") Long userId,
        @Param("type") String type
    );

    List<Recommendation> findByUserUserIdAndRecType(Long userId, String recType);
}
