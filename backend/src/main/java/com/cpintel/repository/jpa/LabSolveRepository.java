package com.cpintel.repository.jpa;

import com.cpintel.entity.LabSolve;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface LabSolveRepository extends JpaRepository<LabSolve, Long> {

    List<LabSolve> findByLabProblemIdIn(Collection<Long> labProblemIds);

    List<LabSolve> findByUserIdAndLabProblemIdIn(Long userId, Collection<Long> labProblemIds);

    @Modifying
    @Query("DELETE FROM LabSolve s WHERE s.labProblemId IN :ids")
    int deleteByLabProblemIdIn(@Param("ids") Collection<Long> ids);

    /**
     * Records a solve. Two checks can find the same one at once (the schedule, and somebody
     * pressing Check), so this is an upsert rather than a read then a write. An on-time solve
     * replaces a late one; nothing else replaces an existing row.
     */
    @Modifying
    @Query(value = """
        INSERT INTO lab_solves (lab_problem_id, user_id, solved_at, late, handle, submission_ref, recorded_at)
        VALUES (:problemId, :userId, :solvedAt, :late, :handle, :ref, now())
        ON CONFLICT (lab_problem_id, user_id) DO UPDATE
           SET solved_at = EXCLUDED.solved_at, late = EXCLUDED.late, handle = EXCLUDED.handle,
               submission_ref = EXCLUDED.submission_ref, recorded_at = EXCLUDED.recorded_at
         WHERE lab_solves.late AND NOT EXCLUDED.late
        """, nativeQuery = true)
    int record(@Param("problemId") Long problemId, @Param("userId") Long userId,
               @Param("solvedAt") Instant solvedAt, @Param("late") boolean late,
               @Param("handle") String handle, @Param("ref") String submissionRef);
}
