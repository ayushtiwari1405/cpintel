package com.cpintel.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;

/** An examination's contest on the judge, when CPIntel created it from the question bank. */
@Entity
@Table(name = "exam_publications")
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExamPublication {

    public enum Status { PUBLISHED, FAILED }

    @Id
    @Column(name = "contest_id")
    private Long contestId;

    @Column(name = "judge_contest_id", nullable = false, length = 100)
    private String judgeContestId;

    /** CPIntel created this contest on the judge; nothing else is ever published into. */
    @Column(name = "contest_created", nullable = false)
    @Builder.Default
    private Boolean contestCreated = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "judge_start")
    private Instant judgeStart;

    @Column(name = "judge_end")
    private Instant judgeEnd;

    @Column(name = "published_by")
    private Long publishedBy;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "note", length = 2000)
    private String note;
}
