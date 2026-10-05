package com.cpintel.repository.jpa;

import com.cpintel.entity.ExamMark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExamMarkRepository extends JpaRepository<ExamMark, Long> {

    List<ExamMark> findByContestId(Long contestId);

    Optional<ExamMark> findByContestIdAndUserIdAndProblemLabel(Long contestId, Long userId,
                                                              String problemLabel);
}
