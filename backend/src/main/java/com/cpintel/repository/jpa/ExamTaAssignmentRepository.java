package com.cpintel.repository.jpa;

import com.cpintel.entity.ExamTaAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExamTaAssignmentRepository extends JpaRepository<ExamTaAssignment, Long> {

    List<ExamTaAssignment> findByContestIdOrderByAssignmentIdAsc(Long contestId);

    List<ExamTaAssignment> findByContestIdAndTaUserId(Long contestId, Long taUserId);

    List<ExamTaAssignment> findByTaUserId(Long taUserId);

    /** A TA taken out of a classroom loses what they were marking in it. */
    @Modifying
    @Query(value = """
        DELETE FROM exam_ta_assignments a
         USING group_contests c
         WHERE a.contest_id = c.contest_id
           AND c.classroom_id = :classroomId
           AND a.ta_user_id = :userId
        """, nativeQuery = true)
    int deleteInClassroom(@Param("classroomId") Long classroomId, @Param("userId") Long userId);
}
