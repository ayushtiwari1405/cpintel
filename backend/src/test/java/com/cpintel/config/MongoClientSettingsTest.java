package com.cpintel.config;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.data.mongo.MongoHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MongoDB client's server selection timeout (MongoConfig.settings), and what it means for the
 * health check when nothing answers. No MongoDB is needed: the unreachable case is a closed port.
 */
class MongoClientSettingsTest {

    @Test
    @DisplayName("the configured timeout applies unless the URI sets its own")
    void timeout() {
        MongoClientSettings ours = MongoConfig.settings("mongodb://u:p@mongodb:27017/cpintel?authSource=cpintel", Duration.ofSeconds(5));
        assertThat(ours.getClusterSettings().getServerSelectionTimeout(TimeUnit.MILLISECONDS)).isEqualTo(5000);
        assertThat(ours.getCredential().getUserName()).isEqualTo("u");

        MongoClientSettings theirs = MongoConfig.settings("mongodb://localhost/cpintel?serverSelectionTimeoutMS=1234", Duration.ofSeconds(5));
        assertThat(theirs.getClusterSettings().getServerSelectionTimeout(TimeUnit.MILLISECONDS)).isEqualTo(1234);
    }

    @Test
    @DisplayName("with no server to reach, the health check says DOWN within the timeout, not after 30 s")
    void unreachableIsDownQuickly() {
        try (MongoClient client = MongoClients.create(MongoConfig.settings("mongodb://127.0.0.1:1/cpintel", Duration.ofSeconds(1)))) {
            MongoHealthIndicator health = new MongoHealthIndicator(new MongoTemplate(client, "cpintel"));
            long t0 = System.nanoTime();
            Health h = health.health();
            assertThat(h.getStatus()).isEqualTo(Status.DOWN);
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
        }
    }
}
