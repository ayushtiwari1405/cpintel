-- A team may hold more than one event on the same judge contest: a practice round and the
-- examination set from it, or the same paper run again for a second sitting. Each event keeps
-- its own standings, roster and session log under its own contest_id, so nothing downstream
-- relied on the pair being unique.
ALTER TABLE group_contests DROP CONSTRAINT uq_gc_group_external;

-- The lookup the constraint used to serve.
CREATE INDEX IF NOT EXISTS idx_gc_group_external
    ON group_contests (group_id, platform, external_id);
