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

@Document(collection = "cf_submissions")
@CompoundIndexes({
    @CompoundIndex(name = "idx_cf_user_verdict", def = "{'userId': 1, 'verdict': 1}"),
    @CompoundIndex(name = "idx_cf_user_created", def = "{'userId': 1, 'createdAt': -1}")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CfSubmission {

    @Id
    private String id;

    @Indexed
    @Field("userId")
    private Long userId;

    @Field("cfSubmissionId")
    private Long cfSubmissionId;

    @Field("contestId")
    private Integer contestId;

    @Field("problemIndex")
    private String problemIndex;

    @Field("problemName")
    private String problemName;

    @Field("problemRating")
    private Integer problemRating;

    @Field("tags")
    private List<String> tags;

    @Field("verdict")
    private String verdict;

    @Field("language")
    private String language;

    @Field("timeConsumedMs")
    private Integer timeConsumedMs;

    @Field("memoryConsumedBytes")
    private Long memoryConsumedBytes;

    @Field("submittedAt")
    private Instant submittedAt;

    @Indexed
    @Field("createdAt")
    private Instant createdAt;

    @Field("normalized")
    private Boolean normalized = false;
}
