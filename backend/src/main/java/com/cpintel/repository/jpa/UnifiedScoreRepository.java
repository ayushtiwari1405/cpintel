package com.cpintel.repository.jpa;

import com.cpintel.entity.UnifiedScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UnifiedScoreRepository extends JpaRepository<UnifiedScore, Long> {
    Optional<UnifiedScore> findByUserUserId(Long userId);
}
