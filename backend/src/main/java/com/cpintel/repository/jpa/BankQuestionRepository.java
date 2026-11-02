package com.cpintel.repository.jpa;

import com.cpintel.entity.BankQuestion;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BankQuestionRepository extends JpaRepository<BankQuestion, Long> {

    List<BankQuestion> findByClassroomIdAndArchivedOrderByUpdatedAtDesc(Long classroomId,
                                                                      Boolean archived);

    /**
     * The question, locked until the transaction ends. Every edit takes this first, so two
     * saves of the same question queue up and the second sees the first one's version.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM BankQuestion q WHERE q.questionId = :id")
    Optional<BankQuestion> findForUpdate(@Param("id") Long id);
}
