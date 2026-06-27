package com.cpintel.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "recommendations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Recommendation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rec_id")
    private Long recId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "rec_type", nullable = false, length = 20)
    private String recType;

    @Lob
    @Column(name = "problem_list", columnDefinition = "CLOB")
    private String problemList;

    @Lob
    @Column(name = "metadata", columnDefinition = "CLOB")
    private String metadata;

    @Column(name = "generated_at")
    private Instant generatedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "is_consumed")
    @Builder.Default
    private Boolean isConsumed = false;

    public enum RecType {
        DAILY, WEEKLY, REVISION, CONTEST_PREP
    }
}
