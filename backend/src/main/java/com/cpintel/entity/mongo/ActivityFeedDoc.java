package com.cpintel.entity.mongo;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.Map;

@Document(collection = "activity_feed")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ActivityFeedDoc {

    @Id
    private String id;

    @Indexed
    @Field("userId") private Long userId;

    @Field("eventType") private String eventType;
    @Field("platform")  private String platform;
    @Field("payload")   private Map<String, Object> payload;

    @Indexed
    @Field("occurredAt") private Instant occurredAt;
}
