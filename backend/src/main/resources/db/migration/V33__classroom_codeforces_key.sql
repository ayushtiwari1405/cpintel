-- A Codeforces API key per classroom, for its private group contests; and whether the
-- classroom's TAs may add and change questions in its bank, which its admins decide.
--
-- A Codeforces API key per classroom, for its private group contests.
--
-- A group contest is visible only to the group's members, so the anonymous API that reads
-- public contests is refused. Signed with a group manager's key, contest.status and
-- contest.standings (asManager=true) return every submission and the problem list. The secret
-- is sealed like the DOMjudge service password; the key alone is useless without it.
ALTER TABLE classrooms ADD COLUMN cf_api_key    VARCHAR(100);
ALTER TABLE classrooms ADD COLUMN cf_api_secret VARCHAR(500);

-- Off until an admin turns it on: TAs then see the bank read-only.
ALTER TABLE classrooms ADD COLUMN tas_write_questions BOOLEAN NOT NULL DEFAULT FALSE;
