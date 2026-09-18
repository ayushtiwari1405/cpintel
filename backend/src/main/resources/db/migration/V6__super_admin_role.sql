-- Adds the SUPER_ADMIN tier.
--
-- Three roles, not two. An ADMIN runs the console day to day — accounts, groups, audit, file
-- policy. Only a SUPER_ADMIN can change what someone else *is*, which keeps the one privilege
-- that can manufacture more privilege in a single pair of hands.
--
-- The check constraint is the reason this needs a migration at all: without widening it, every
-- attempt to store the new role fails at the database rather than in the service, which is a
-- confusing place to discover a typo in a role name.

ALTER TABLE users DROP CONSTRAINT chk_role;

ALTER TABLE users
  ADD CONSTRAINT chk_role CHECK (role IN ('USER', 'ADMIN', 'SUPER_ADMIN'));

-- Existing admins keep exactly the rights they had. Promotion to SUPER_ADMIN is deliberate:
-- it happens through cpintel.admin.bootstrap-email, so that a deployment cannot acquire one
-- silently as a side effect of running a migration.
