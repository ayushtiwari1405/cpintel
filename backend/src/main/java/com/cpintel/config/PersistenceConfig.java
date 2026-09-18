package com.cpintel.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Where the persistence layer is switched on.
 *
 * <p>These used to sit on {@code CpIntelApplication}. Annotations on the application class are
 * part of the root configuration of <em>every</em> context built from it, including the sliced
 * ones {@code @WebMvcTest} creates — and a web slice has no entities, so JPA auditing failed to
 * start with "JPA metamodel must not be empty" and took the whole context with it. That made the
 * authorisation tests impossible to write without also booting a database.
 *
 * <p>Moved here, they are picked up normally at runtime and left out of the web slices, which is
 * both the fix and the more honest place for them: the application class says what the
 * application is, not how it talks to Postgres.
 */
@Configuration
@EnableJpaAuditing
@EnableMongoRepositories(basePackages = "com.cpintel.repository.mongo")
public class PersistenceConfig {
}
