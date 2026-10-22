package com.cpintel.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.jdbc.DataSourceHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Duration;

/**
 * The database's health check, answering within {@code cpintel.health.timeout}.
 *
 * <p>Named as Spring Boot names its own ({@code dbHealthContributor}), which it then does not
 * create, so /actuator/health still reports a "db" component - now within the bound rather than
 * after the pool's 30 s connection timeout (see {@link BoundedHealthIndicator}). MongoDB is
 * bounded at its client instead ({@link MongoConfig#settings}), and Redis by its command timeout.
 */
@Configuration
public class HealthConfig {

    @Bean(name = "dbHealthContributor")
    public HealthIndicator dbHealthContributor(DataSource dataSource,
                                               @Value("${cpintel.health.timeout:5s}") Duration bound) {
        return new BoundedHealthIndicator("database", new DataSourceHealthIndicator(dataSource), bound);
    }
}
