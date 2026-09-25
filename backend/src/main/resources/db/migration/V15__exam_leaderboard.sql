-- An examination's own leaderboard, ranked from CPIntel's submission archive rather than the
-- judge's scoreboard: solved count first, then total time, where each solve counts from the
-- moment CPIntel sent the accepted code (not when the verdict came back) plus a configurable
-- penalty per earlier wrong attempt.
--
-- The board candidates see is a snapshot, recomputed at most every refresh_minutes, so it is
-- stored here rather than in one instance's memory.
ALTER TABLE group_contests
    ADD COLUMN leaderboard_enabled         BOOLEAN DEFAULT TRUE  NOT NULL,
    ADD COLUMN leaderboard_refresh_minutes INTEGER DEFAULT 15    NOT NULL,
    ADD COLUMN wrong_penalty_minutes       INTEGER DEFAULT 0     NOT NULL,
    ADD COLUMN leaderboard_final_public    BOOLEAN DEFAULT TRUE  NOT NULL,
    ADD COLUMN leaderboard_snapshot        TEXT,
    ADD COLUMN leaderboard_generated_at    TIMESTAMPTZ;
