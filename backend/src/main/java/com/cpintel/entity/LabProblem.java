package com.cpintel.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/** One problem of a lab: where it lives, what it is called, and what it is worth. */
@Entity
@Table(name = "lab_problems")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabProblem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "lab_problem_id")
    private Long labProblemId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lab_id", nullable = false)
    private LabAssignment lab;

    /** CODEFORCES or LEETCODE. */
    @Column(name = "platform", nullable = false, length = 20)
    private String platform;

    /** {@code "1700/A"} on Codeforces, the title slug on LeetCode. */
    @Column(name = "problem_key", nullable = false, length = 120)
    private String problemKey;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "url", nullable = false, length = 500)
    private String url;

    @Column(name = "difficulty", length = 20)
    private String difficulty;

    @Builder.Default
    @Column(name = "marks", nullable = false, precision = 10, scale = 2)
    private BigDecimal marks = BigDecimal.ONE;

    @Column(name = "ordering", nullable = false)
    private Integer ordering;
}
