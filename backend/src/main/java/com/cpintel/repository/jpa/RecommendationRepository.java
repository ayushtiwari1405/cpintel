package com.cpintel.repository.jpa;

import com.cpintel.entity.Recommendation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
        AND r.is_consumed = false
        AND (r.expires_at IS NULL OR r.expires_at > now())
        ORDER BY r.generated_at DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<Recommendation> findLatestActiveByUserAndType(
        @Param("userId") Long userId,
        @Param("type") String type
    );

    List<Recommendation> findByUserUserIdAndRecType(Long userId, String recType);

    /**
     * Retire every live sheet of one type for a user, so a freshly generated sheet is
     * the only active row. Called before inserting the replacement.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE Recommendation r SET r.isConsumed = true
        WHERE r.user.userId = :userId AND r.recType = :type AND r.isConsumed = false
        """)
    void markConsumed(@Param("userId") Long userId, @Param("type") String type);
}
