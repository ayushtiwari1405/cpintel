package com.cpintel.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One candidate's own code for one examination.
 *
 * <p>The examination's shared password says a sitting has begun; this says which seat the
 * person typing is sitting in. Both are needed because neither answers the other's question —
 * everybody in the room has the shared password by the time the paper starts, so on its own it
 * cannot distinguish a candidate from somebody who heard it read out through a door.
 *
 * <p><b>The code is stored encrypted, not hashed.</b> See
 * {@link com.cpintel.events.ExamPasswordService} for the reasoning; the short version is that
 * an invigilator has to be able to print the desk slips in the morning and re-read one code for
 * a candidate whose slip went missing at eleven, and a hash can do neither.
 *
 * <p>{@code firstUsedAt} and {@code useCount} are for the invigilator rather than for any rule.
 * A code first used twenty minutes in, or one that has opened the paper from two places, is
 * worth a look and is not something software should rule on — the same framing the event log
 * already uses.
 */
@Entity
@Table(name = "exam_passcodes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExamPasscode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "passcode_id")
    private Long passcodeId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contest_id", nullable = false)
    private GroupContest contest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** AES-256-GCM, base64. Never returned by anything but the admin reveal route. */
    @Column(name = "code", nullable = false, length = 500)
    private String code;

    @Column(name = "issued_at", nullable = false)
    @Builder.Default
    private Instant issuedAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issued_by")
    private User issuedBy;

    @Column(name = "first_used_at")
    private Instant firstUsedAt;

    @Column(name = "use_count", nullable = false)
    @Builder.Default
    private Integer useCount = 0;

    /**
     * Never let the code reach a log line, a stack trace or an error body.
     *
     * Written out by hand for the same reason {@code DomjudgeCredentialStore.Stored} is: a
     * generated {@code toString()} would carry the secret to the first place this object is
     * interpolated into a string, and that place is usually a log.
     */
    @Override
    public String toString() {
        return "ExamPasscode[id=" + passcodeId + ", used=" + useCount + "]";
    }
}
