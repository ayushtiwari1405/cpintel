package com.cpintel.entity.mongo;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.List;

@Document(collection = "lc_submissions")
@CompoundIndexes({
    @CompoundIndex(name = "idx_lc_user_status", def = "{'userId': 1, 'status': 1}"),
    @CompoundIndex(name = "idx_lc_user_created", def = "{'userId': 1, 'createdAt': -1}")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LcSubmission {

    @Id
    private String id;

    @Indexed
    @Field("userId")
    private Long userId;

    @Field("lcSubmissionId")
    private Long lcSubmissionId;

    @Field("titleSlug")
    private String titleSlug;

    @Field("problemTitle")
    private String problemTitle;

    @Field("difficulty")
    private String difficulty;

    @Field("tags")
    private List<String> tags;

    @Field("status")
    private String status;

    @Field("language")
    private String language;

    @Field("runtimeMs")
    private Integer runtimeMs;

    @Field("memoryMb")
    private Double memoryMb;

    @Field("submittedAt")
    private Instant submittedAt;

    @Indexed
    @Field("createdAt")
    private Instant createdAt;

    @Field("normalized")
    private Boolean normalized = false;
}
