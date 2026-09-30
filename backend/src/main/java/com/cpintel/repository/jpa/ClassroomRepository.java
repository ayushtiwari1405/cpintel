package com.cpintel.repository.jpa;

import com.cpintel.entity.Classroom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClassroomRepository extends JpaRepository<Classroom, Long> {

    List<Classroom> findAllByOrderByNameAsc();

    @Query("SELECT c FROM Classroom c WHERE lower(c.domjudgeUrl) = lower(:url)")
    Optional<Classroom> findByDomjudgeUrl(@Param("url") String url);

    /** The classrooms an admin runs: the ones they own, and the ones they were added to. */
    @Query(value = """
        SELECT c.* FROM classrooms c
         WHERE c.owner_id = :userId
            OR EXISTS (SELECT 1 FROM classroom_staff s
                        WHERE s.classroom_id = c.classroom_id AND s.user_id = :userId)
         ORDER BY c.name
        """, nativeQuery = true)
    List<Classroom> findManagedBy(@Param("userId") Long userId);

    @Query(value = """
        SELECT EXISTS (SELECT 1 FROM classroom_staff
                        WHERE classroom_id = :classroomId AND user_id = :userId)
        """, nativeQuery = true)
    boolean isStaff(@Param("classroomId") Long classroomId, @Param("userId") Long userId);

    @Query(value = "SELECT user_id FROM classroom_staff WHERE classroom_id = :classroomId",
        nativeQuery = true)
    List<Long> staffIds(@Param("classroomId") Long classroomId);

    @Modifying
    @Query(value = """
        INSERT INTO classroom_staff (classroom_id, user_id) VALUES (:classroomId, :userId)
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    void addStaff(@Param("classroomId") Long classroomId, @Param("userId") Long userId);

    @Modifying
    @Query(value = "DELETE FROM classroom_staff WHERE classroom_id = :classroomId AND user_id = :userId",
        nativeQuery = true)
    void removeStaff(@Param("classroomId") Long classroomId, @Param("userId") Long userId);
}
