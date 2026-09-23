package com.cpintel.repository.jpa;

import com.cpintel.entity.ExamPasscode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExamPasscodeRepository extends JpaRepository<ExamPasscode, Long> {

    @Query("SELECT p FROM ExamPasscode p WHERE p.contest.contestId = :contestId "
         + "AND p.user.userId = :userId")
    Optional<ExamPasscode> find(@Param("contestId") Long contestId,
                                @Param("userId") Long userId);

    /**
     * Every code for an examination, with the people fetched.
     *
     * The admin screen that reads this prints one row per candidate, so the join is not an
     * optimisation — without it this is the roster's worth of extra queries, issued while an
     * invigilator waits to print the slips.
     */
    @Query("SELECT p FROM ExamPasscode p JOIN FETCH p.user "
         + "WHERE p.contest.contestId = :contestId ORDER BY p.user.username")
    List<ExamPasscode> findAllForContest(@Param("contestId") Long contestId);

    @Query("SELECT COUNT(p) FROM ExamPasscode p WHERE p.contest.contestId = :contestId")
    long countForContest(@Param("contestId") Long contestId);

    @Modifying
    @Query("DELETE FROM ExamPasscode p WHERE p.contest.contestId = :contestId")
    int deleteAllForContest(@Param("contestId") Long contestId);

    @Modifying
    @Query("DELETE FROM ExamPasscode p WHERE p.contest.contestId = :contestId "
         + "AND p.user.userId = :userId")
    int deleteFor(@Param("contestId") Long contestId, @Param("userId") Long userId);
}
