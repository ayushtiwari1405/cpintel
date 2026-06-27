package com.cpintel.repository.jpa;

import com.cpintel.entity.TopicMastery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TopicMasteryRepository extends JpaRepository<TopicMastery, Long> {
    List<TopicMastery> findByUserUserId(Long userId);
    Optional<TopicMastery> findByUserUserIdAndTopic(Long userId, String topic);

    @Query("SELECT tm FROM TopicMastery tm WHERE tm.user.userId = :userId ORDER BY tm.masteryScore ASC")
    List<TopicMastery> findWeakTopics(@Param("userId") Long userId);

    @Query("SELECT tm FROM TopicMastery tm WHERE tm.user.userId = :userId ORDER BY tm.masteryScore DESC")
    List<TopicMastery> findStrongTopics(@Param("userId") Long userId);

    @Query(value = """
        SELECT * FROM topic_mastery tm
        WHERE tm.user_id = :userId
        AND tm.last_practiced_at < SYSTIMESTAMP - INTERVAL '14' DAY
        ORDER BY tm.decay_score DESC
        """, nativeQuery = true)
    List<TopicMastery> findForgottenTopics(@Param("userId") Long userId);
}
