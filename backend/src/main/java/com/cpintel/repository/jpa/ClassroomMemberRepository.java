package com.cpintel.repository.jpa;

import com.cpintel.entity.ClassroomMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClassroomMemberRepository extends JpaRepository<ClassroomMember, Long> {

    @Query("SELECT m FROM ClassroomMember m JOIN FETCH m.user WHERE m.classroomId = :classroomId ORDER BY m.user.username")
    List<ClassroomMember> findByClassroom(@Param("classroomId") Long classroomId);

    Optional<ClassroomMember> findByClassroomIdAndUserUserId(Long classroomId, Long userId);

    boolean existsByClassroomIdAndUserUserId(Long classroomId, Long userId);

    @Query("SELECT m.classroomId FROM ClassroomMember m WHERE m.user.userId = :userId")
    List<Long> classroomIdsOf(@Param("userId") Long userId);

    long countByClassroomId(Long classroomId);

    void deleteByClassroomIdAndUserUserId(Long classroomId, Long userId);
}
