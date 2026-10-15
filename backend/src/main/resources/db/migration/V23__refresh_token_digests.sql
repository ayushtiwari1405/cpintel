-- Refresh tokens are stored as their SHA-256, and each belongs to a family.
--
-- The digest: a refresh token is a week-long session, and the table held them as issued, so a
-- copy of it (a backup, a dump, a read-only SQL injection) was a set of working sign-ins.
-- Password-reset tokens were already stored this way. Existing rows are converted in place,
-- so nobody is signed out by the upgrade: the browser still presents the raw token, and the
-- backend now looks it up by its digest.
--
-- The family: every token renewed from a sign-in shares that sign-in's family_id. Rotation marks
-- the presented token rotated_at. If a token that was already rotated is presented again, two
-- parties hold it — the owner and whoever copied it — and the whole family is revoked. Tokens
-- that predate this have no history to share, so each starts a family of its own.
UPDATE refresh_tokens SET token = encode(sha256(convert_to(token, 'UTF8')), 'hex');

ALTER TABLE refresh_tokens
    ADD COLUMN family_id  VARCHAR(36),
    ADD COLUMN rotated_at TIMESTAMPTZ;

UPDATE refresh_tokens SET family_id = gen_random_uuid()::text;

ALTER TABLE refresh_tokens ALTER COLUMN family_id SET NOT NULL;

CREATE INDEX idx_rt_family ON refresh_tokens (family_id);
