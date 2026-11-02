package com.cpintel.repository.jpa;

import com.cpintel.entity.BankQuestionStatementPdf;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface BankQuestionStatementPdfRepository extends JpaRepository<BankQuestionStatementPdf, Long> {

    /** What the editor shows about an uploaded statement, without reading its bytes. */
    interface Info {
        String getFileName();
        Integer getSizeBytes();
        Integer getPages();
        Long getUploadedBy();
        Instant getUploadedAt();
    }

    @Query("SELECT p.fileName AS fileName, p.sizeBytes AS sizeBytes, p.pages AS pages, "
        + "p.uploadedBy AS uploadedBy, p.uploadedAt AS uploadedAt "
        + "FROM BankQuestionStatementPdf p WHERE p.questionId = :questionId")
    Optional<Info> findInfo(@Param("questionId") Long questionId);
}
