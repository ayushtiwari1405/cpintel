package com.cpintel.repository.jpa;

import com.cpintel.entity.TopicMastery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface TopicMasteryRepository extends JpaRepository<TopicMastery, Long> {

    List<TopicMastery> findByUserUserId(Long userId);

    Optional<TopicMastery> findByUserUserIdAndTopic(Long userId, String topic);

    /**
     * One scope's rows. The radar wants the {@code TOPIC} roll-ups; the skill tree and the
     * revision queue want the {@code NODE} rows.
     */
    List<TopicMastery> findByUserUserIdAndScope(Long userId, String scope);

    @Query("SELECT tm FROM TopicMastery tm WHERE tm.user.userId = :userId "
         + "AND tm.scope = :scope ORDER BY tm.masteryScore ASC")
    List<TopicMastery> findWeakest(@Param("userId") Long userId, @Param("scope") String scope);

    @Query("SELECT tm FROM TopicMastery tm WHERE tm.user.userId = :userId "
         + "AND tm.scope = :scope ORDER BY tm.masteryScore DESC")
    List<TopicMastery> findStrongest(@Param("userId") Long userId, @Param("scope") String scope);

    /**
     * Skills that have faded and are worth revisiting, most-faded first.
     *
     * <p>Rows with no {@code last_practiced_at} are excluded rather than treated as infinitely
     * stale: a skill never practised has nothing to revise, it has something to learn, and the
     * two belong in different queues.
     */
    @Query("SELECT tm FROM TopicMastery tm WHERE tm.user.userId = :userId "
         + "AND tm.scope = :scope AND tm.lastPracticedAt IS NOT NULL "
         + "AND tm.decayScore > :minDecay ORDER BY tm.decayScore DESC")
    List<TopicMastery> findDecayed(@Param("userId") Long userId,
                                   @Param("scope") String scope,
                                   @Param("minDecay") double minDecay);

    /**
     * Records the counts for one skill, whether or not a row already exists.
     *
     * <p>Replaces a read-then-write that raced. Two refreshes for the same user — the dashboard
     * fires one, the nightly pass fires another, or the client simply sends two — would each
     * look for a row, each find none on a first run, and each insert; one won and the other came
     * back as a 500 on {@code uq_tm_user_topic}. Postgres resolves that atomically, so whichever
     * arrives second updates instead of failing.
     *
     * <p>Only the columns this write owns are touched on conflict. The score columns belong to
     * the scoring pass and would otherwise be reset to their insert-time defaults every time
     * somebody refreshed, quietly wiping the mastery figures the whole product is built on.
     *
     * <p>{@code lastPracticedAt} is now the genuine date of the user's most recent work on this
     * skill, taken from the submission history. It used to be passed {@code Instant.now()} on
     * every refresh, which meant every skill always looked practised today: recency was always
     * maximal, decay was always zero, the revision queue's {@code decay > threshold} filter never
     * matched anything, and the entire spaced-repetition feature silently produced nothing.
     *
     * <p>Native because {@code ON CONFLICT} has no JPQL equivalent. The constraint is named
     * rather than inferred from a column list so that a schema change which drops it fails here,
     * loudly, instead of silently going back to racing.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        INSERT INTO topic_mastery (user_id, topic, scope, parent_topic, track,
                                   problems_solved, problems_attempted, last_practiced_at)
        VALUES (:userId, :topic, :scope, :parentTopic, :track,
                :solved, :attempted, :lastPracticedAt)
        ON CONFLICT ON CONSTRAINT uq_tm_user_topic DO UPDATE SET
            scope              = EXCLUDED.scope,
            parent_topic       = EXCLUDED.parent_topic,
            track              = EXCLUDED.track,
            problems_solved    = EXCLUDED.problems_solved,
            problems_attempted = EXCLUDED.problems_attempted,
            last_practiced_at  = EXCLUDED.last_practiced_at
        """, nativeQuery = true)
    void upsertCounts(@Param("userId") Long userId,
                      @Param("topic") String topic,
                      @Param("scope") String scope,
                      @Param("parentTopic") String parentTopic,
                      @Param("track") String track,
                      @Param("solved") int solved,
                      @Param("attempted") int attempted,
                      @Param("lastPracticedAt") Instant lastPracticedAt);

    /**
     * Drops rows for skills the user no longer has any evidence for.
     *
     * <p>Needed because the taxonomy changes. A node that is renamed or removed leaves a row
     * behind that nothing will ever update again, and it would keep appearing in the radar and
     * the revision queue as a skill frozen at whatever it last scored.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM TopicMastery tm WHERE tm.user.userId = :userId "
         + "AND tm.topic NOT IN :keep")
    void deleteStale(@Param("userId") Long userId, @Param("keep") List<String> keep);
}
