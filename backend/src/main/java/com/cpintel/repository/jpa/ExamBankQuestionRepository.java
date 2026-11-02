package com.cpintel.repository.jpa;

import com.cpintel.entity.ExamBankQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExamBankQuestionRepository extends JpaRepository<ExamBankQuestion, ExamBankQuestion.Key> {

    List<ExamBankQuestion> findByContestIdOrderByOrdinalAsc(Long contestId);

    /** The examinations a question is in, for the bank's editor to say so. */
    List<ExamBankQuestion> findByQuestionId(Long questionId);
}
