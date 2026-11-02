package com.cpintel.repository.jpa;

import com.cpintel.entity.BankQuestionTest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface BankQuestionTestRepository extends JpaRepository<BankQuestionTest, Long> {

    /** Samples first, then secret tests, each in order. */
    @Query("SELECT t FROM BankQuestionTest t WHERE t.questionId = :questionId "
        + "ORDER BY t.sample DESC, t.ordinal ASC")
    List<BankQuestionTest> findOrdered(@Param("questionId") Long questionId);

    /** Per question: samples, secret tests and bytes stored, for the bank's list. */
    @Query(value = """
        SELECT question_id,
               COUNT(*) FILTER (WHERE sample)     AS samples,
               COUNT(*) FILTER (WHERE NOT sample) AS secret,
               COALESCE(SUM(input_bytes + COALESCE(answer_bytes, 0)), 0) AS bytes
          FROM bank_question_tests
         WHERE question_id IN (:questionIds)
         GROUP BY question_id
        """, nativeQuery = true)
    List<Object[]> countsFor(@Param("questionIds") Collection<Long> questionIds);
}
