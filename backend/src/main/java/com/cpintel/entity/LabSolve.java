package com.cpintel.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** The accepted submission that credited a student with a lab problem. */
@Entity
@Table(name = "lab_solves")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabSolve {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "solve_id")
    private Long solveId;

    @Column(name = "lab_problem_id", nullable = false)
    private Long labProblemId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** When the platform says it was accepted. */
    @Column(name = "solved_at", nullable = false)
    private Instant solvedAt;

    /** Accepted after the deadline: shown, worth nothing. */
    @Column(name = "late", nullable = false)
    private Boolean late;

    /** The handle it was read from, as it was then. */
    @Column(name = "handle", nullable = false, length = 100)
    private String handle;

    /** The platform's submission id. */
    @Column(name = "submission_ref", length = 100)
    private String submissionRef;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;
}
