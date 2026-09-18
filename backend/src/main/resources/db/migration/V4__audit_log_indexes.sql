-- The audit trail became a read surface with the admin console.
--
-- Until now nothing wrote to audit_log, so the two indexes in V2 were enough for a table that
-- was only ever inserted into in theory. Every sign-in and every administrative change is
-- recorded there now, which makes it the fastest-growing table in the schema and the one the
-- console pages through constantly.
--
-- The three queries that matter, and what each index is for:
--   the trail filtered by action, newest first      -> idx_al_action_created
--   one account's history on its detail screen      -> idx_al_user_created
--   the last sign-in for a page of users            -> idx_al_login_user (partial)
--
-- The last one is partial on purpose: LOGIN rows are a small slice of the table, and an index
-- covering only them stays small no matter how much other activity accumulates.

CREATE INDEX IF NOT EXISTS idx_al_action_created ON audit_log(action, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_al_user_created   ON audit_log(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_al_login_user     ON audit_log(user_id, created_at DESC)
    WHERE action = 'LOGIN';
