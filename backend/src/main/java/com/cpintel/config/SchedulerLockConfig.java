package com.cpintel.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Makes the scheduled jobs safe to run on more than one instance.
 *
 * <p>Spring runs every {@code @Scheduled} method on every instance it is started on. With a
 * single deployment that is invisible, which is exactly what made it dangerous: authentication
 * here is stateless and sessions live in Redis, so the application <em>looks</em> horizontally
 * scalable, and the first time anyone acted on that the nightly analytics pass would have begun
 * running twice concurrently over the same rows and the three-minute standings refresh would
 * have started racing itself.
 *
 * <p>Every job now claims a named lock before it runs and the others skip that tick. The claim
 * carries an expiry rather than being released only on completion, so an instance that is killed
 * mid-run does not hold the job hostage until someone notices — the lock lapses and the next
 * scheduled tick picks it up.
 *
 * <p>{@code defaultLockAtMostFor} is the backstop for a job that forgets to declare its own. It
 * is deliberately generous: releasing a lock while the work is still running would reintroduce
 * exactly the concurrency this exists to prevent, and the cost of holding one too long is only
 * a skipped tick.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName("shedlock")
                // Postgres has a real timestamp type and the server clock is the one thing every
                // instance already agrees on; using it avoids trusting each JVM's clock to be
                // in step with the others, which is the usual way this goes wrong.
                .usingDbTime()
                .build());
    }
}
