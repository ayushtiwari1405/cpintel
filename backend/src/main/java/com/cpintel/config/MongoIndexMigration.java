package com.cpintel.config;

import com.mongodb.client.MongoCollection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Drops the global unique indexes that made one account's sync steal another's submissions.
 *
 * <p>{@code cf_submissions} and {@code lc_submissions} were keyed unique on the platform's
 * submission id alone. Two CPIntel accounts linking the same handle is ordinary — someone
 * re-registers, an admin tests with their own handle — and with a global key the second
 * account's sync found every submission "already stored" under the first account's userId
 * and stored nothing, leaving its analytics, mastery and dashboard empty. The keys are now
 * unique per user (see the entities and {@code mongo-init.js}); this removes the old ones from
 * databases created before that, which {@code mongo-init.js} never runs against again.
 *
 * <p>Also drops the LeetCode and CodeChef submission collections, which nothing reads since
 * those platforms were removed.
 *
 * <p>Idempotent: once the old index and collections are gone this finds nothing to do.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MongoIndexMigration implements ApplicationRunner {

    private final MongoTemplate mongo;

    @Override
    public void run(ApplicationArguments args) {
        dropGlobalUnique("cf_submissions", "cfSubmissionId");
        // LeetCode and CodeChef were dropped; their synced history goes with them. It was
        // public data, re-fetchable from the judges, and nothing reads it any more.
        dropCollection("lc_submissions");
        dropCollection("cc_submissions");
    }

    private void dropCollection(String collection) {
        try {
            if (mongo.collectionExists(collection)) {
                mongo.dropCollection(collection);
                log.info("Dropped {} — LeetCode and CodeChef are no longer synced", collection);
            }
        } catch (Exception e) {
            log.warn("Could not drop {}: {}", collection, e.getMessage());
        }
    }

    private void dropGlobalUnique(String collection, String field) {
        try {
            MongoCollection<Document> coll = mongo.getCollection(collection);
            for (Document index : coll.listIndexes()) {
                Document key = index.get("key", Document.class);
                boolean globalUnique = Boolean.TRUE.equals(index.getBoolean("unique"))
                    && key != null && key.size() == 1 && key.containsKey(field);
                if (globalUnique) {
                    coll.dropIndex(index.getString("name"));
                    log.info("Dropped global unique index {} on {}; submissions are now unique "
                        + "per user", index.getString("name"), collection);
                }
            }
        } catch (Exception e) {
            // Not fatal: the app runs, and a second account on the same handle stays empty
            // until the index is dropped by hand.
            log.warn("Could not migrate the {} indexes: {}", collection, e.getMessage());
        }
    }
}
