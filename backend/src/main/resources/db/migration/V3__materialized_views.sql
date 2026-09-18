-- Materialized views, refreshed nightly by AnalyticsScheduler (previously two
-- DBMS_SCHEDULER jobs calling DBMS_MVIEW.REFRESH).
--
--   Oracle BUILD DEFERRED / REFRESH COMPLETE ON DEMAND -> WITH NO DATA + REFRESH MATERIALIZED VIEW
--   TRUNC(ts)                                          -> ts::date
--
-- Each view carries a unique index so the scheduler can use REFRESH ... CONCURRENTLY,
-- which avoids taking an exclusive lock while readers are querying it.

CREATE MATERIALIZED VIEW mv_user_topic_summary AS
SELECT
    tm.user_id,
    tm.topic,
    tm.mastery_score,
    tm.confidence_score,
    tm.decay_score,
    tm.problems_solved,
    tm.last_practiced_at,
    CASE
        WHEN tm.mastery_score >= 80 THEN 'STRONG'
        WHEN tm.mastery_score >= 50 THEN 'MODERATE'
        WHEN tm.mastery_score >= 20 THEN 'WEAK'
        ELSE 'UNTOUCHED'
    END AS mastery_band
FROM topic_mastery tm
WITH NO DATA;

CREATE UNIQUE INDEX idx_mv_uts_user_topic ON mv_user_topic_summary(user_id, topic);

CREATE MATERIALIZED VIEW mv_contest_stats AS
SELECT
    cs.user_id,
    cs.platform,
    COUNT(*)                  AS total_contests,
    AVG(cs.rating_change)     AS avg_rating_change,
    MAX(cs.rating_after)      AS peak_rating,
    AVG(cs.wrong_submissions) AS avg_wrong_subs,
    AVG(cs.problems_solved)   AS avg_problems_solved,
    MIN(cs.first_solve_mins)  AS best_first_solve_mins
FROM contest_summaries cs
GROUP BY cs.user_id, cs.platform
WITH NO DATA;

CREATE UNIQUE INDEX idx_mv_cstats_user_platform ON mv_contest_stats(user_id, platform);

CREATE MATERIALIZED VIEW mv_daily_activity AS
SELECT
    tm.user_id,
    tm.last_practiced_at::date AS activity_date,
    COUNT(DISTINCT tm.topic)   AS topics_practiced,
    SUM(tm.problems_solved)    AS problems_solved
FROM topic_mastery tm
WHERE tm.last_practiced_at IS NOT NULL
GROUP BY tm.user_id, tm.last_practiced_at::date
WITH NO DATA;

CREATE UNIQUE INDEX idx_mv_daily_user_date ON mv_daily_activity(user_id, activity_date);
