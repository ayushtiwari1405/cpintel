package com.cpintel.analytics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcCall;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class OracleProcedureCaller {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    // ── PKG_ANALYTICS ──────────────────────────────────────────

    public Double calculateMastery(Long userId, String topic) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT pkg_analytics.calculate_mastery(?, ?) FROM DUAL",
                Double.class, userId, topic
            );
        } catch (Exception e) {
            log.warn("calculate_mastery failed for user={} topic={}: {}", userId, topic, e.getMessage());
            return 0.0;
        }
    }

    public Double calculateAccuracy(Long userId, String platform) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT pkg_analytics.calculate_accuracy(?, ?) FROM DUAL",
                Double.class, userId, platform
            );
        } catch (Exception e) {
            log.warn("calculate_accuracy failed: {}", e.getMessage());
            return 0.0;
        }
    }

    public Double calculateConsistency(Long userId) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT pkg_analytics.calculate_consistency(?) FROM DUAL",
                Double.class, userId
            );
        } catch (Exception e) {
            log.warn("calculate_consistency failed: {}", e.getMessage());
            return 50.0;
        }
    }

    public Double calculateDecay(Long masteryId) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT pkg_analytics.calculate_decay(?) FROM DUAL",
                Double.class, masteryId
            );
        } catch (Exception e) {
            log.warn("calculate_decay failed: {}", e.getMessage());
            return 0.0;
        }
    }

    public void refreshAllMastery(Long userId) {
        try {
            new SimpleJdbcCall(dataSource)
                .withCatalogName("pkg_analytics")
                .withProcedureName("refresh_all_mastery")
                .execute(Map.of("p_user_id", userId));
            log.debug("refresh_all_mastery completed for user {}", userId);
        } catch (Exception e) {
            log.error("refresh_all_mastery failed for user {}: {}", userId, e.getMessage());
        }
    }

    // ── PKG_RECOMMENDATION ─────────────────────────────────────

    public void generateDailySheet(Long userId) {
        try {
            new SimpleJdbcCall(dataSource)
                .withCatalogName("pkg_recommendation")
                .withProcedureName("generate_daily_sheet")
                .execute(Map.of("p_user_id", userId));
        } catch (Exception e) {
            log.error("generate_daily_sheet failed for user {}: {}", userId, e.getMessage());
        }
    }

    public void generateWeeklySheet(Long userId) {
        try {
            new SimpleJdbcCall(dataSource)
                .withCatalogName("pkg_recommendation")
                .withProcedureName("generate_weekly_sheet")
                .execute(Map.of("p_user_id", userId));
        } catch (Exception e) {
            log.error("generate_weekly_sheet failed for user {}: {}", userId, e.getMessage());
        }
    }

    public void generateRevisionSchedule(Long userId) {
        try {
            new SimpleJdbcCall(dataSource)
                .withCatalogName("pkg_recommendation")
                .withProcedureName("generate_revision_schedule")
                .execute(Map.of("p_user_id", userId));
        } catch (Exception e) {
            log.error("generate_revision_schedule failed for user {}: {}", userId, e.getMessage());
        }
    }

    // ── PKG_CONTEST ────────────────────────────────────────────

    public void analyzeContest(Long userId, Long contestId) {
        try {
            new SimpleJdbcCall(dataSource)
                .withCatalogName("pkg_contest")
                .withProcedureName("analyze_contest")
                .execute(Map.of("p_user_id", userId, "p_contest_id", contestId));
        } catch (Exception e) {
            log.error("analyze_contest failed: {}", e.getMessage());
        }
    }

    public void detectBehaviorPatterns(Long userId) {
        try {
            new SimpleJdbcCall(dataSource)
                .withCatalogName("pkg_contest")
                .withProcedureName("detect_behavior_patterns")
                .execute(Map.of("p_user_id", userId));
        } catch (Exception e) {
            log.error("detect_behavior_patterns failed: {}", e.getMessage());
        }
    }

    // ── PKG_UNIFIED_RATING ─────────────────────────────────────

    public Double computeUnifiedScore(Long userId) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT pkg_unified_rating.compute_unified_score(?) FROM DUAL",
                Double.class, userId
            );
        } catch (Exception e) {
            log.error("compute_unified_score failed: {}", e.getMessage());
            return 0.0;
        }
    }

    public void updateAllScores() {
        try {
            new SimpleJdbcCall(dataSource)
                .withCatalogName("pkg_unified_rating")
                .withProcedureName("update_all_scores")
                .execute();
        } catch (Exception e) {
            log.error("update_all_scores failed: {}", e.getMessage());
        }
    }
}
