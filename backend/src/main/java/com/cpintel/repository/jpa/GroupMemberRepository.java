package com.cpintel.repository.jpa;

import com.cpintel.entity.GroupMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {

    @Query("SELECT m FROM GroupMember m JOIN FETCH m.user WHERE m.group.groupId = :groupId ORDER BY m.user.username")
    List<GroupMember> findByGroup(@Param("groupId") Long groupId);

    Optional<GroupMember> findByGroupGroupIdAndUserUserId(Long groupId, Long userId);

    boolean existsByGroupGroupIdAndUserUserId(Long groupId, Long userId);

    void deleteByGroupGroupIdAndUserUserId(Long groupId, Long userId);

    long countByGroupGroupId(Long groupId);

    /** Takes someone out of every team in one classroom, when they leave it. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM GroupMember m WHERE m.user.userId = :userId AND m.group.groupId IN "
        + "(SELECT g.groupId FROM ContestGroup g WHERE g.classroomId = :classroomId)")
    int deleteFromClassroom(@Param("classroomId") Long classroomId, @Param("userId") Long userId);
}
