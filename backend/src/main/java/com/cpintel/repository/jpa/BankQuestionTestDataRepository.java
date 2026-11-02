package com.cpintel.repository.jpa;

import com.cpintel.entity.BankQuestionTestData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BankQuestionTestDataRepository extends JpaRepository<BankQuestionTestData, Long> {
}
