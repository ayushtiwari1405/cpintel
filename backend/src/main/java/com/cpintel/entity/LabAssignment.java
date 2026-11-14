package com.cpintel.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A lab: problems on Codeforces and LeetCode a classroom's students are set to solve by a date.
 *
 * <p>The problems are solved on those sites, not in CPIntel. What is kept here is the list,
 * the window, and (in {@link LabSolve}) which student's accepted submission credited which
 * problem — read from the student's verified handle on each platform.
 */
@Entity
@DynamicUpdate
@Table(name = "lab_assignments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabAssignment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "lab_id")
    private Long labId;

    @Column(name = "classroom_id", nullable = false)
    private Long classroomId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", length = 4000)
    private String description;

    /** Students see the lab from here, and solves count from here. */
    @Column(name = "opens_at", nullable = false)
    private Instant opensAt;

    /** Solves after this are recorded as late and earn no marks. */
    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    /** Whether a problem accepted before the lab opened counts. */
    @Builder.Default
    @Column(name = "count_earlier_solves", nullable = false)
    private Boolean countEarlierSolves = false;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    /** The groups it is set for; empty means every student in the classroom. */
    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "lab_groups", joinColumns = @JoinColumn(name = "lab_id"))
    @Column(name = "group_id")
    private Set<Long> groupIds = new HashSet<>();

    @Builder.Default
    @OneToMany(mappedBy = "lab", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ordering ASC")
    private List<LabProblem> problems = new ArrayList<>();
}
