package com.cpintel.repository.jpa;

import com.cpintel.entity.BankQuestionCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface BankQuestionCheckRepository extends JpaRepository<BankQuestionCheck, Long> {

    /** Checks a restart cut short: nothing will finish them now. */
    @Modifying
    @Query("UPDATE BankQuestionCheck c SET c.status = com.cpintel.entity.BankQuestionCheck.Status.ERROR, "
        + "c.finishedAt = :now, c.summary = 'Stopped when the server restarted. Run the check again.' "
        + "WHERE c.status IN (com.cpintel.entity.BankQuestionCheck.Status.QUEUED, "
        + "com.cpintel.entity.BankQuestionCheck.Status.RUNNING)")
    int abandonActive(Instant now);
}
