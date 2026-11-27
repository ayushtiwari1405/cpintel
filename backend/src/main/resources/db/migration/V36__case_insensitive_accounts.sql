-- One account per name and address, whatever the capitals.
--
-- users.username and users.email were unique only case-sensitively, while sign-in matches them
-- ignoring case: registering STU_AB beside stu_ab made sign-in fail for both.
--
-- Accounts that already differ only in capitals are not renamed or merged here — which one is
-- the real owner is not something a script can know. The upgrade stops and names them by id;
-- rename or merge them, then start again. scripts/deploy-preflight.py reports the same before
-- anything is changed.

DO $$
DECLARE
    clashes TEXT;
BEGIN
    SELECT string_agg(ids, '; ') INTO clashes FROM (
        SELECT 'username of users ' || string_agg(user_id::text, ', ' ORDER BY user_id) AS ids
          FROM users GROUP BY lower(username) HAVING count(*) > 1
        UNION ALL
        SELECT 'email of users ' || string_agg(user_id::text, ', ' ORDER BY user_id)
          FROM users GROUP BY lower(email) HAVING count(*) > 1
    ) AS duplicates;

    IF clashes IS NOT NULL THEN
        RAISE EXCEPTION 'Accounts differ only in capitals (%). Rename or merge them, then upgrade.',
            clashes;
    END IF;
END $$;

CREATE UNIQUE INDEX uq_users_username_lower ON users (lower(username));
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));
