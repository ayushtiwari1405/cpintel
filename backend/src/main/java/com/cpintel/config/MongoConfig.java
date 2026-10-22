package com.cpintel.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.convert.MongoConverter;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableMongoAuditing
public class MongoConfig {

    @Value("${spring.data.mongodb.uri}")
    private String mongoUri;

    @Value("${spring.data.mongodb.database:cpintel}")
    private String databaseName;

    /** How long an operation waits for a reachable server; see {@link #settings}. */
    @Value("${cpintel.mongo.server-selection-timeout:5s}")
    private Duration serverSelectionTimeout;

    @Bean
    public MongoClient mongoClient() {
        return MongoClients.create(settings(mongoUri, serverSelectionTimeout));
    }

    /**
     * The URI's settings, with a server selection timeout of {@code serverSelection} unless the
     * URI sets {@code serverSelectionTimeoutMS} itself.
     *
     * <p>The driver's default is 30 s: with MongoDB unreachable, every operation - the health
     * check included - waited that long before failing, so /actuator/health took 30 s to say
     * DOWN and requests hung as long. It only applies while no server can be reached at all;
     * waiting for a pooled connection under load is a separate limit, left as it is.
     */
    static MongoClientSettings settings(String uri, Duration serverSelection) {
        ConnectionString connection = new ConnectionString(uri);
        MongoClientSettings.Builder builder = MongoClientSettings.builder().applyConnectionString(connection);
        if (connection.getServerSelectionTimeout() == null) {
            builder.applyToClusterSettings(c -> c.serverSelectionTimeout(serverSelection.toMillis(), TimeUnit.MILLISECONDS));
        }
        return builder.build();
    }

    @Bean
    public MongoDatabaseFactory mongoDatabaseFactory() {
        return new SimpleMongoClientDatabaseFactory(mongoClient(), databaseName);
    }

    /**
     * With Spring Boot's converter, whose mapping context carries
     * {@code spring.data.mongodb.auto-index-creation}. The one-argument constructor builds a
     * mapping context of its own with index creation off, so no {@code @Indexed} or
     * {@code @CompoundIndex} was ever created outside mongo-init.js - among them the unique
     * indexes PersonalFileService and ContestFilePolicy rely on to turn a race into a 409.
     */
    @Bean
    public MongoTemplate mongoTemplate(MongoConverter converter) {
        return new MongoTemplate(mongoDatabaseFactory(), converter);
    }
}
