-- Node-level mastery.
--
-- Mastery was kept against fourteen coarse topic names while the skill tree held its own,
-- finer set of nodes, and the unlock check joined the two on the node's *display name* -- which
-- is not a topic name, so almost every node read a mastery of zero and stayed LOCKED forever.
--
-- The tree is now the taxonomy. A row is either one skill-tree node (scope = 'NODE', topic
-- holding the node's stable id) or one coarse roll-up derived from those nodes (scope =
-- 'TOPIC', topic holding the display name). Both live in this table because everything that
-- reads mastery -- scoring, decay, revision, the radar -- wants to treat them identically.
--
-- uq_tm_user_topic still holds: node ids are kebab-case and roll-up names are title-case
-- words, so the two namespaces cannot collide, and existing rows stay valid as TOPIC rows
-- without being rewritten.

-- Two materialized views select topic_mastery.topic, and Postgres refuses to alter the type of
-- a column a view depends on. They are dropped here and recreated below rather than left as a
-- reason not to widen the column.
DROP MATERIALIZED VIEW IF EXISTS mv_daily_activity;
DROP MATERIALIZED VIEW IF EXISTS mv_user_topic_summary;

ALTER TABLE topic_mastery
    ADD COLUMN IF NOT EXISTS scope        VARCHAR(10)  NOT NULL DEFAULT 'TOPIC',
    ADD COLUMN IF NOT EXISTS parent_topic VARCHAR(100),
    ADD COLUMN IF NOT EXISTS track        VARCHAR(60);

ALTER TABLE topic_mastery
    DROP CONSTRAINT IF EXISTS chk_tm_scope;

ALTER TABLE topic_mastery
    ADD CONSTRAINT chk_tm_scope CHECK (scope IN ('NODE', 'TOPIC'));

-- The topic column now holds node ids as well as display names.
ALTER TABLE topic_mastery
    ALTER COLUMN topic TYPE VARCHAR(120);

-- Reading one scope at a time is the common access pattern: the radar wants every TOPIC row
-- for a user, the tree wants every NODE row.
CREATE INDEX IF NOT EXISTS idx_tm_user_scope
    ON topic_mastery (user_id, scope);

-- The revision queue orders by what has faded most, over rows that have actually been touched.
CREATE INDEX IF NOT EXISTS idx_tm_user_revision
    ON topic_mastery (user_id, revision_score)
    WHERE last_practiced_at IS NOT NULL;

-- last_practiced_at was being overwritten with now() on every analytics refresh, so no row was
-- ever more than a few hours old and nothing could decay. Rows written under that bug carry a
-- timestamp that means "the last time analytics ran", not "the last time this was practised",
-- and there is no way to tell the two apart after the fact. Clearing it lets the next refresh
-- write the real value from the submission history; a NULL reads as "never practised", which
-- is the safe interpretation until the true date is recovered.
UPDATE topic_mastery SET last_practiced_at = NULL;

-- Recreated with scope exposed, so a reader can ask for one granularity instead of getting a
-- mix of nodes and roll-ups in the same result.
CREATE MATERIALIZED VIEW mv_user_topic_summary AS
SELECT
    tm.user_id,
    tm.topic,
    tm.scope,
    tm.parent_topic,
    tm.track,
    tm.mastery_score,
    tm.confidence_score,
    tm.decay_score,
    tm.revision_score,
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

-- Restricted to the roll-ups. Summing problems_solved across every row would count a single
-- problem once for each skill-tree node it is evidence for, and a mid-level DP problem is
-- evidence for several.
--
-- Note this view groups skills by the one date they were last practised, which is not the same
-- thing as a daily activity feed; the trends endpoint builds that from the submission history
-- directly. This is kept for the reporting shape that already existed.
CREATE MATERIALIZED VIEW mv_daily_activity AS
SELECT
    tm.user_id,
    tm.last_practiced_at::date AS activity_date,
    COUNT(DISTINCT tm.topic)   AS topics_practiced,
    SUM(tm.problems_solved)    AS problems_solved
FROM topic_mastery tm
WHERE tm.last_practiced_at IS NOT NULL
  AND tm.scope = 'TOPIC'
GROUP BY tm.user_id, tm.last_practiced_at::date
WITH NO DATA;

CREATE UNIQUE INDEX idx_mv_daily_user_date ON mv_daily_activity(user_id, activity_date);
