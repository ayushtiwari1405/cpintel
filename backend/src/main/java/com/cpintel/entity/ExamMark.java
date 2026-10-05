package com.cpintel.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A mark set by hand for one student on one question of an examination.
 *
 * <p>It replaces the judge's all-or-nothing mark on the leaderboard and in the export. No row
 * means the judge's mark stands.
 */
@Entity
@Table(name = "exam_marks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExamMark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mark_id")
    private Long markId;

    @Column(name = "contest_id", nullable = false)
    private Long contestId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "problem_label", nullable = false, length = 16)
    private String problemLabel;

    @Column(name = "marks", nullable = false, precision = 10, scale = 2)
    private BigDecimal marks;

    @Column(name = "remark", length = 1000)
    private String remark;

    /** The archived submission that was on screen when the mark was given. */
    @Column(name = "submission_id", length = 64)
    private String submissionId;

    @Column(name = "marked_by")
    private Long markedBy;

    @Column(name = "marked_at", nullable = false)
    private Instant markedAt;
}
