package com.cpintel.repository.jpa;

import com.cpintel.entity.GroupStanding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GroupStandingRepository extends JpaRepository<GroupStanding, Long> {

    @Query("""
        SELECT s FROM GroupStanding s JOIN FETCH s.user
        WHERE s.contest.contestId = :contestId
        ORDER BY s.groupRank ASC NULLS LAST, s.solved DESC
        """)
    List<GroupStanding> findByContest(@Param("contestId") Long contestId);

    void deleteByContestContestId(Long contestId);
}
