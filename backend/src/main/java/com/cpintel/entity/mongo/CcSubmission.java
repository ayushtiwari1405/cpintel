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

@Document(collection = "cc_submissions")
@CompoundIndexes({
    @CompoundIndex(name = "idx_cc_user_result", def = "{'userId': 1, 'result': 1}"),
    @CompoundIndex(name = "idx_cc_user_created", def = "{'userId': 1, 'createdAt': -1}")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CcSubmission {

    @Id
    private String id;

    @Indexed
    @Field("userId")
    private Long userId;

    @Field("problemCode")
    private String problemCode;

    @Field("problemName")
    private String problemName;

    @Field("contestCode")
    private String contestCode;

    @Field("tags")
    private List<String> tags;

    @Field("result")
    private String result;

    @Field("language")
    private String language;

    @Field("timeMs")
    private Integer timeMs;

    @Field("submittedAt")
    private Instant submittedAt;

    @Indexed
    @Field("createdAt")
    private Instant createdAt;

    @Field("normalized")
    private Boolean normalized = false;
}
