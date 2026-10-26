-- Which admin made an account, so an admin re-passwords only accounts staff made.
--
-- Being in an admin's classroom is not enough: any admin can enrol any account, and an account
-- in no classroom at all would then pass as theirs (audit run-1 F2). NULL is an account its
-- owner registered, or one whose maker is no longer known; only a super admin may change its
-- password, email or standing.
ALTER TABLE users ADD COLUMN managed_by BIGINT REFERENCES users (user_id) ON DELETE SET NULL;

-- Accounts made before this column existed, from the audit entry that recorded them. Single
-- creation writes "<id>:<role>", roster import "<id>:USER:bulk", a new TA "<id>:USER:ta";
-- the first entry wins.
UPDATE users u
   SET managed_by = made.admin_id
  FROM (SELECT DISTINCT ON (split_part(entity_id, ':', 1))
               split_part(entity_id, ':', 1)::BIGINT AS user_id, user_id AS admin_id
          FROM audit_log
         WHERE action = 'ADMIN_USER_CREATED'
           AND entity_type = 'USER'
           AND user_id IS NOT NULL
           AND split_part(entity_id, ':', 1) ~ '^[0-9]{1,18}$'
         ORDER BY split_part(entity_id, ':', 1), created_at) made
 WHERE u.user_id = made.user_id
   AND u.user_id <> made.admin_id
   AND EXISTS (SELECT 1 FROM users a WHERE a.user_id = made.admin_id);
