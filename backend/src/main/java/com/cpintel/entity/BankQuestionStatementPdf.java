package com.cpintel.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** A bank question's statement as the setter's own PDF, used instead of the rendered Markdown. */
@Entity
@Table(name = "bank_question_statement_pdfs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankQuestionStatementPdf {

    @Id
    @Column(name = "question_id")
    private Long questionId;

    @Column(name = "pdf", nullable = false)
    private byte[] pdf;

    @Column(name = "file_name", nullable = false, length = 200)
    private String fileName;

    @Column(name = "size_bytes", nullable = false)
    private Integer sizeBytes;

    @Column(name = "pages", nullable = false)
    private Integer pages;

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @PrePersist
    void onCreate() {
        if (uploadedAt == null) uploadedAt = Instant.now();
    }
}
