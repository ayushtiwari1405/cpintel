package com.cpintel.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** A message to everyone sitting an examination. */
@Entity
@Table(name = "exam_announcements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExamAnnouncement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "announcement_id")
    private Long announcementId;

    @Column(name = "contest_id", nullable = false)
    private Long contestId;

    @Column(name = "message", nullable = false, length = 1000)
    private String message;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
