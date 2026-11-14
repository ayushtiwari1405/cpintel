-- CSES joins labs: problems from cses.fi, and a CSES account per student.
--
-- A CSES account is connected through CSES's own API sign-in: CPIntel asks cses.fi for a
-- token, the student approves it on cses.fi, and the token then acts for that student. It is
-- proof of the account by itself, so a CSES handle is verified from the moment it is
-- connected. The token is sealed like a Codeforces session and never leaves the server.
ALTER TABLE platform_accounts DROP CONSTRAINT chk_platform;
ALTER TABLE platform_accounts ADD  CONSTRAINT chk_platform
    CHECK (platform IN ('CODEFORCES', 'LEETCODE', 'CSES'));
ALTER TABLE platform_accounts ADD COLUMN access_token VARCHAR(600);

ALTER TABLE lab_problems DROP CONSTRAINT chk_lp_platform;
ALTER TABLE lab_problems ADD  CONSTRAINT chk_lp_platform
    CHECK (platform IN ('CODEFORCES', 'LEETCODE', 'CSES'));
