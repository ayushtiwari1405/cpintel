package com.cpintel.repository.jpa;

import com.cpintel.entity.LabAssignment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface LabAssignmentRepository extends JpaRepository<LabAssignment, Long> {

    List<LabAssignment> findByClassroomIdOrderByDueAtDesc(Long classroomId);

    /** The lab with its problems loaded, for use outside a request's session. */
    @EntityGraph(attributePaths = {"problems", "groupIds"})
    @Query("SELECT l FROM LabAssignment l WHERE l.labId = :labId")
    java.util.Optional<LabAssignment> findWithProblems(@Param("labId") Long labId);

    /** Opened labs in these classrooms, newest deadline first: what a student can see. */
    @EntityGraph(attributePaths = {"problems", "groupIds"})
    @Query("SELECT DISTINCT l FROM LabAssignment l WHERE l.classroomId IN :classroomIds AND l.opensAt <= :now "
         + "ORDER BY l.dueAt DESC")
    List<LabAssignment> findOpenedIn(@Param("classroomIds") Collection<Long> classroomIds,
                                     @Param("now") Instant now);

    /** Labs still worth checking: opened, and not more than the late window past due. */
    @EntityGraph(attributePaths = {"problems", "groupIds"})
    @Query("SELECT DISTINCT l FROM LabAssignment l WHERE l.opensAt <= :now AND l.dueAt >= :dueAfter")
    List<LabAssignment> findCheckable(@Param("now") Instant now, @Param("dueAfter") Instant dueAfter);

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE LabAssignment l SET l.lastCheckedAt = :at WHERE l.labId IN :ids")
    int markChecked(@Param("ids") Collection<Long> ids, @Param("at") Instant at);
}
