package com.cpintel.entity.mongo;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * An admin's decision about personal files for one contest — or, with a null contest id, for
 * every contest at once.
 *
 * Absence is the normal state. A contest with no row here follows the deployment default,
 * which ships enabled, so today every contest allows personal files without anyone touching
 * this collection. A row exists only where an admin has deliberately departed from that,
 * which is also what makes the admin listing readable: it is the list of exceptions, not a
 * mirror of every contest that has ever been opened.
 */
@Document(collection = "contest_file_rules")
@CompoundIndexes({
    @CompoundIndex(name = "idx_file_rule_contest",
        def = "{'platform': 1, 'contestId': 1}", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContestFileRule {

    /** Platform of the global row — the one that overrides the configured default. */
    public static final String GLOBAL = "*";

    @Id
    private String id;

    /** CODEFORCES or DOMJUDGE, or {@link #GLOBAL} for the deployment-wide default. */
    @Field("platform")
    private String platform;

    /** Null on the global row. Text, because DOMjudge contest ids are not numbers. */
    @Field("contestId")
    private String contestId;

    @Field("enabled")
    private Boolean enabled;

    /** Why the admin set it, shown back to them in the listing. */
    @Field("note")
    private String note;

    /** User id of the admin who last touched this row. */
    @Field("updatedBy")
    private Long updatedBy;

    @Field("updatedAt")
    private Instant updatedAt;
}
