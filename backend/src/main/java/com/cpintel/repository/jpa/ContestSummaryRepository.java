package com.cpintel.repository.jpa;

import com.cpintel.entity.ContestSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContestSummaryRepository extends JpaRepository<ContestSummary, Long> {
    List<ContestSummary> findByUserUserIdOrderByContestDateDesc(Long userId);
    List<ContestSummary> findByUserUserIdAndPlatform(Long userId, String platform);
    Optional<ContestSummary> findByUserUserIdAndContestCfId(Long userId, String contestCfId);

    @Query(value = """
        SELECT cs.* FROM contest_summaries cs
        WHERE cs.user_id = :userId
        ORDER BY cs.contest_date DESC
        FETCH FIRST :limit ROWS ONLY
        """, nativeQuery = true)
    List<ContestSummary> findRecentContests(@Param("userId") Long userId, @Param("limit") int limit);

    @Query(value = """
        SELECT AVG(cs.rating_change) FROM contest_summaries cs
        WHERE cs.user_id = :userId AND cs.platform = :platform
        """, nativeQuery = true)
    Double getAvgRatingChange(@Param("userId") Long userId, @Param("platform") String platform);
}
