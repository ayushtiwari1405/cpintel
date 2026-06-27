package com.cpintel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching
@EnableScheduling
@EnableJpaAuditing
@EnableMongoRepositories(basePackages = "com.cpintel.repository.mongo")
public class CpIntelApplication {
    public static void main(String[] args) {
        SpringApplication.run(CpIntelApplication.class, args);
    }
}
