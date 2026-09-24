package com.cpintel.entity.mongo;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * One Codeforces problemset statement, as it was scraped and sanitised.
 *
 * <p>Kept because a statement can only be fetched past Cloudflare with somebody's live
 * {@code cf_clearance}, which lasts hours. A published statement never changes, so the first
 * successful read is good for every user forever: storing it means a problem anyone has opened
 * keeps opening after that clearance has lapsed, and after a restart. Rating and tags are not
 * stored — they come from the problemset API, which is not challenged and does change.
 */
@Document(collection = "cf_statements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CfStatementDoc {

    /** "{contestId}/{INDEX}", e.g. "2266/A". */
    @Id
    private String id;

    private String name;
    private String timeLimit;
    private String memoryLimit;
    private String inputFile;
    private String outputFile;
    private String legendHtml;
    private String inputSpecHtml;
    private String outputSpecHtml;
    private String noteHtml;
    private List<Sample> samples;
    private String url;
    private Instant fetchedAt;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Sample {
        private String input;
        private String output;
    }
}
