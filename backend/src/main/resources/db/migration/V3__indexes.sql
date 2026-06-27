-- Users
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_users_email ON users(email)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_users_username ON users(username)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_users_active ON users(is_active)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/

-- Platform accounts
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_pa_user_id ON platform_accounts(user_id)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_pa_platform ON platform_accounts(platform)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/

-- Contest summaries
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_cs_user_id ON contest_summaries(user_id)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_cs_platform ON contest_summaries(platform)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_cs_contest_date ON contest_summaries(contest_date DESC)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/

-- Topic mastery
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_tm_user_id ON topic_mastery(user_id)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_tm_topic ON topic_mastery(topic)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_tm_mastery_score ON topic_mastery(mastery_score DESC)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/

-- Recommendations
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_rec_user_id ON recommendations(user_id)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_rec_type ON recommendations(rec_type)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_rec_expires ON recommendations(expires_at)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/

-- Revision schedule
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_rs_user_id ON revision_schedule(user_id)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_rs_next_rev ON revision_schedule(next_revision_at)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/

-- Refresh tokens
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_rt_user_id ON refresh_tokens(user_id)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_rt_token ON refresh_tokens(token)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_rt_expires ON refresh_tokens(expires_at)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/

-- Sync jobs
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_sj_user_id ON sync_jobs(user_id)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_sj_status ON sync_jobs(status)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/

-- Audit log
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_al_user_id ON audit_log(user_id)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_al_created_at ON audit_log(created_at DESC)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
