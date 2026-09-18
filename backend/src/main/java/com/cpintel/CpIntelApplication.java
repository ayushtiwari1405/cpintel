package com.cpintel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// Caching is enabled by RedisConfig and persistence by PersistenceConfig, next to the beans
// each of them configures, rather than accumulating here.
@SpringBootApplication
@EnableScheduling
public class CpIntelApplication {
    public static void main(String[] args) {
        SpringApplication.run(CpIntelApplication.class, args);
    }
}
