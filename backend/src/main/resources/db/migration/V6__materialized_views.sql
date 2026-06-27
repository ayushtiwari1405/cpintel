CREATE MATERIALIZED VIEW mv_user_topic_summary
    BUILD DEFERRED
    REFRESH COMPLETE ON DEMAND
AS
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
FROM topic_mastery tm;

CREATE MATERIALIZED VIEW mv_contest_stats
    BUILD DEFERRED
    REFRESH COMPLETE ON DEMAND
AS
SELECT
    cs.user_id,
    cs.platform,
    COUNT(*)                          AS total_contests,
    AVG(cs.rating_change)             AS avg_rating_change,
    MAX(cs.rating_after)              AS peak_rating,
    AVG(cs.wrong_submissions)         AS avg_wrong_subs,
    AVG(cs.problems_solved)           AS avg_problems_solved,
    MIN(cs.first_solve_mins)          AS best_first_solve_mins
FROM contest_summaries cs
GROUP BY cs.user_id, cs.platform;

CREATE MATERIALIZED VIEW mv_daily_activity
    BUILD DEFERRED
    REFRESH COMPLETE ON DEMAND
AS
SELECT
    tm.user_id,
    TRUNC(tm.last_practiced_at)       AS activity_date,
    COUNT(DISTINCT tm.topic)          AS topics_practiced,
    SUM(tm.problems_solved)           AS problems_solved
FROM topic_mastery tm
WHERE tm.last_practiced_at IS NOT NULL
GROUP BY tm.user_id, TRUNC(tm.last_practiced_at);
