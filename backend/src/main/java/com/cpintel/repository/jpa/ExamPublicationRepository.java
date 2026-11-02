package com.cpintel.repository.jpa;

import com.cpintel.entity.ExamPublication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ExamPublicationRepository extends JpaRepository<ExamPublication, Long> {
}
