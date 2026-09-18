package com.cpintel.repository.jpa;

import com.cpintel.entity.ContestGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContestGroupRepository extends JpaRepository<ContestGroup, Long> {

    List<ContestGroup> findAllByOrderByCreatedAtDesc();

    @Query("SELECT g FROM ContestGroup g LEFT JOIN FETCH g.members m LEFT JOIN FETCH m.user WHERE g.groupId = :id")
    Optional<ContestGroup> findByIdWithMembers(@Param("id") Long id);

    /** The groups this person belongs to — their own view of the feature. */
    @Query("""
        SELECT g FROM ContestGroup g JOIN g.members m
        WHERE m.user.userId = :userId AND g.isActive = true
        ORDER BY g.name
        """)
    List<ContestGroup> findForMember(@Param("userId") Long userId);

    boolean existsByNameIgnoreCase(String name);
}
