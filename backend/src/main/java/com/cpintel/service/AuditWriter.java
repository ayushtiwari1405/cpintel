package com.cpintel.service;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes audit rows over a small pool of its own, outside every request's transaction.
 *
 * <p>Audit entries used to be written in a {@code REQUIRES_NEW} transaction, which takes a
 * second connection from the request pool while the caller still holds its first. With the
 * pool at 20 and 200 request threads, a room of candidates unlocking at the bell had twenty
 * threads each holding one connection and waiting for another: nothing moved until the pool's
 * 30 s timeout, and every request behind them failed (measured at 400 candidates). A pool the
 * audit alone draws on cannot be emptied by callers that are waiting for it.
 *
 * <p>Each insert is its own committed statement, as the {@code REQUIRES_NEW} one was: an entry
 * for a refused unlock survives the rollback of the request that refused it.
 */
@Component
@Slf4j
public class AuditWriter {

    private static final String INSERT = "INSERT INTO audit_log (user_id, classroom_id, action, "
        + "entity_type, entity_id, ip_address, user_agent, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, now())";

    private final HikariDataSource pool;
    private final JdbcTemplate jdbc;

    public AuditWriter(DataSourceProperties properties) {
        this.pool = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        pool.setPoolName("AuditPool");
        pool.setMaximumPoolSize(4);
        pool.setMinimumIdle(1);
        // An audit write must never be the reason a request is held up; it gives up and logs.
        pool.setConnectionTimeout(5_000);
        this.jdbc = new JdbcTemplate(pool);
    }

    void insert(Long userId, Long classroomId, String action, String entityType, String entityId,
                String ip, String userAgent) {
        jdbc.update(INSERT, userId, classroomId, action, entityType, entityId, ip, userAgent);
    }

    @PreDestroy
    void close() {
        pool.close();
    }
}
