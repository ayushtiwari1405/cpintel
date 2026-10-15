-- An examination unlock, kept in Postgres as well as Redis.
--
-- Unlocking a paper (typing the room password) mints a grant in Redis that the submission gate
-- reads. Redis is the fast copy; this is the one that survives it. If Redis restarts empty, or
-- cannot be reached, mid-paper, the gate reads the grant from here and puts it back, instead of
-- sending a whole room back to the unlock screen at once. Null means not unlocked; the number is
-- the password generation the unlock was made under, so rotating the password still ends every
-- grant from before.
ALTER TABLE exam_passcodes ADD COLUMN unlocked_generation INTEGER;
