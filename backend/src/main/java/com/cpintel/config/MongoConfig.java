package com.cpintel.config;

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

@Configuration
@EnableMongoAuditing
public class MongoConfig {

    @Value("${spring.data.mongodb.uri}")
    private String mongoUri;

    @Value("${spring.data.mongodb.database:cpintel}")
    private String databaseName;

    @Bean
    public MongoClient mongoClient() {
        return MongoClients.create(mongoUri);
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
