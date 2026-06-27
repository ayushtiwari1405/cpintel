package com.cpintel.entity.mongo;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.Map;

@Document(collection = "contest_snapshots")
@CompoundIndex(name = "idx_snap_user_platform", def = "{'userId': 1, 'platform': 1}")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ContestSnapshotDoc {

    @Id
    private String id;

    @Field("userId")   private Long userId;
    @Field("platform") private String platform;
    @Field("contestId") private String contestId;
    @Field("snapshotData") private Map<String, Object> snapshotData;
    @Field("capturedAt") private Instant capturedAt;
}
