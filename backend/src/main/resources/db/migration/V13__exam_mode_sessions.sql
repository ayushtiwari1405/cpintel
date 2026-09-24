-- Examination mode.
--
-- A candidate signs in to an examination with the examination password on their own slip
-- instead of their account password, and gets a session that can reach that paper and nothing
-- else. Their ordinary session, in turn, cannot reach a paper that has not ended. The access
-- token carries the examination it is for; the refresh token has to carry it too, so that
-- renewing a session mid-paper yields another examination session rather than an ordinary one —
-- and so that it stops renewing when the paper ends.
ALTER TABLE refresh_tokens
    ADD COLUMN exam_id BIGINT REFERENCES group_contests (contest_id) ON DELETE CASCADE;
