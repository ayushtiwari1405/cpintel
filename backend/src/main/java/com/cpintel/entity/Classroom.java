package com.cpintel.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * One DOMjudge instance, and everything run on it.
 *
 * <p>A classroom is what a student is enrolled in. It owns exactly one judge — its URL is
 * unique across classrooms — and the teams, events and judge logins that belong to that judge.
 * A student taking two courses is in two classrooms, with a different team account on each,
 * and CPIntel keeps those logins side by side rather than letting the second replace the first.
 *
 * <p>The judge hangs off the classroom rather than the other way round because not every
 * assessment will need one: a quiz CPIntel marks itself still belongs to a class of people.
 */
@Entity
@Table(name = "classrooms")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Classroom extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "classroom_id")
    private Long classroomId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    /** The DOMjudge root, without {@code /api}. */
    @Column(name = "domjudge_url", length = 500)
    private String domjudgeUrl;

    /** The optional contest-wide account; see {@code DomjudgeClient}. */
    @Column(name = "service_username", length = 100)
    private String serviceUsername;

    /** AES-GCM ciphertext, never the password itself. Written only through ClassroomService. */
    @Column(name = "service_password", length = 500)
    private String servicePassword;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}
