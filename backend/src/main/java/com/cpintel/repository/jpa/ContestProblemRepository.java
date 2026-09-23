package com.cpintel.repository.jpa;

import com.cpintel.entity.ContestProblem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContestProblemRepository extends JpaRepository<ContestProblem, Long> {

    List<ContestProblem> findByContestContestIdOrderByOrderingAscLabelAsc(Long contestId);

    Optional<ContestProblem> findByContestContestIdAndLabelIgnoreCase(Long contestId, String label);

    void deleteByContestContestIdAndLabelIgnoreCase(Long contestId, String label);
}
