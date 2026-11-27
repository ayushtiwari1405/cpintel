-- Two kinds of row the application accepted and no longer does.
--
-- 1. Events that nobody could have sat but that candidates can see. "End" on a draft or a
--    waiting event published it, and could save an end before its start; "Archive" did the
--    same to a paper that had not begun. Both reveal an unsat paper, so they go back to draft.
--    A draft ended or archived after its planned start cannot be told from a real one by its
--    timestamps; scripts/postgres-upgrade-check.sh lists the candidates for a person to read.
--    One whose people left a session log was sat, and is left alone.

UPDATE group_contests c
   SET lifecycle = 'DRAFT', archived_at = NULL
 WHERE (c.ends_at < c.starts_at
        OR (c.lifecycle = 'ARCHIVED'
            AND (c.starts_at IS NULL OR c.archived_at < c.starts_at)))
   AND c.completed_at IS NULL
   AND NOT EXISTS (SELECT 1 FROM exam_events e WHERE e.contest_id = c.contest_id);

-- 2. Avatar addresses that are not web addresses, such as javascript: ones. Left in place they
--    would also stop their owners saving their profile.

UPDATE users
   SET avatar_url = NULL
 WHERE avatar_url IS NOT NULL
   AND avatar_url <> ''
   AND avatar_url !~* '^https?://\S+$';
