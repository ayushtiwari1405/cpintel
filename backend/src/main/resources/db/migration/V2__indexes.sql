-- Secondary indexes. The Oracle original wrapped every CREATE INDEX in an
-- EXECUTE IMMEDIATE / EXCEPTION WHEN OTHERS block to make reruns idempotent;
-- Postgres has IF NOT EXISTS, so the blocks are gone.

-- Users
CREATE INDEX IF NOT EXISTS idx_users_email    ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);
CREATE INDEX IF NOT EXISTS idx_users_active   ON users(is_active);

-- Platform accounts
CREATE INDEX IF NOT EXISTS idx_pa_user_id  ON platform_accounts(user_id);
CREATE INDEX IF NOT EXISTS idx_pa_platform ON platform_accounts(platform);

-- Contest summaries
CREATE INDEX IF NOT EXISTS idx_cs_user_id      ON contest_summaries(user_id);
CREATE INDEX IF NOT EXISTS idx_cs_platform     ON contest_summaries(platform);
CREATE INDEX IF NOT EXISTS idx_cs_contest_date ON contest_summaries(contest_date DESC);

-- Topic mastery
CREATE INDEX IF NOT EXISTS idx_tm_user_id       ON topic_mastery(user_id);
CREATE INDEX IF NOT EXISTS idx_tm_topic         ON topic_mastery(topic);
CREATE INDEX IF NOT EXISTS idx_tm_mastery_score ON topic_mastery(mastery_score DESC);

-- Recommendations
CREATE INDEX IF NOT EXISTS idx_rec_user_id ON recommendations(user_id);
CREATE INDEX IF NOT EXISTS idx_rec_type    ON recommendations(rec_type);
CREATE INDEX IF NOT EXISTS idx_rec_expires ON recommendations(expires_at);

-- Revision schedule
CREATE INDEX IF NOT EXISTS idx_rs_user_id  ON revision_schedule(user_id);
CREATE INDEX IF NOT EXISTS idx_rs_next_rev ON revision_schedule(next_revision_at);

-- Roadmap nodes (unique per user+node_key, from the V15 taxonomy upgrade)
CREATE UNIQUE INDEX IF NOT EXISTS idx_rn_user_nodekey ON roadmap_nodes(user_id, node_key);

-- Refresh tokens
CREATE INDEX IF NOT EXISTS idx_rt_user_id ON refresh_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_rt_token   ON refresh_tokens(token);
CREATE INDEX IF NOT EXISTS idx_rt_expires ON refresh_tokens(expires_at);

-- Sync jobs
CREATE INDEX IF NOT EXISTS idx_sj_user_id ON sync_jobs(user_id);
CREATE INDEX IF NOT EXISTS idx_sj_status  ON sync_jobs(status);

-- Audit log
CREATE INDEX IF NOT EXISTS idx_al_user_id    ON audit_log(user_id);
CREATE INDEX IF NOT EXISTS idx_al_created_at ON audit_log(created_at DESC);
