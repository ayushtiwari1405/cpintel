package com.cpintel.analytics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Rebuilds the reporting materialized views.
 *
 * <p>Oracle refreshed these from a DBMS_SCHEDULER job calling DBMS_MVIEW.REFRESH.
 * Postgres has no in-database scheduler, so the trigger moved to
 * {@code AnalyticsScheduler} and the statements live here.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MaterializedViewRefresher {

    /** Declared in V3__materialized_views.sql; each has a unique index for CONCURRENTLY. */
    private static final List<String> VIEWS = List.of(
        "mv_user_topic_summary",
        "mv_contest_stats",
        "mv_daily_activity"
    );

    private final JdbcTemplate jdbcTemplate;

    /** Refresh every view. One failure is logged and does not stop the others. */
    public void refreshAll() {
        log.info("Refreshing {} materialized views", VIEWS.size());
        for (String view : VIEWS) {
            try {
                refresh(view);
            } catch (Exception e) {
                log.error("Refresh failed for {}: {}", view, e.getMessage());
            }
        }
    }

    /**
     * Refresh one view, CONCURRENTLY where possible so readers are not locked out for
     * the duration.
     *
     * <p>The views are created WITH NO DATA, and Postgres rejects a CONCURRENTLY
     * refresh on a view that has never been populated — so the first run for each view
     * has to be an ordinary refresh, which takes an exclusive lock. After that every
     * refresh is concurrent.
     */
    private void refresh(String view) {
        // View names come from the constant above, never from user input, so
        // interpolating them into DDL is safe here — Postgres will not accept them
        // as bind parameters.
        if (isPopulated(view)) {
            jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY " + view);
        } else {
            log.info("{} has never been populated; doing a non-concurrent first refresh", view);
            jdbcTemplate.execute("REFRESH MATERIALIZED VIEW " + view);
        }
        log.debug("Refreshed {}", view);
    }

    private boolean isPopulated(String view) {
        Boolean populated = jdbcTemplate.queryForObject(
            "SELECT relispopulated FROM pg_class WHERE relname = ? AND relkind = 'm'",
            Boolean.class, view);
        return Boolean.TRUE.equals(populated);
    }
}
