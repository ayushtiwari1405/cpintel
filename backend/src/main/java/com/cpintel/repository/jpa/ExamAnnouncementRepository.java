package com.cpintel.repository.jpa;

import com.cpintel.entity.ExamAnnouncement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExamAnnouncementRepository extends JpaRepository<ExamAnnouncement, Long> {

    List<ExamAnnouncement> findByContestIdOrderByCreatedAtAsc(Long contestId);
}
