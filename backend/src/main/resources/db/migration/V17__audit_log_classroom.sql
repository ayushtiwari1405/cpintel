-- The audit log, filtered by classroom.
--
-- An admin reads the entries of the classrooms they run, and their own; a superadmin reads
-- everything. So each entry records the classroom it happened in, when it happened in one.
-- Null is a deployment-wide entry (a sign-in, a role change).

ALTER TABLE audit_log ADD COLUMN classroom_id BIGINT;

CREATE INDEX idx_audit_classroom ON audit_log (classroom_id, created_at DESC);

-- Best effort for what was written before, from the ids the entries already name. Anything
-- this cannot place stays deployment-wide, which errs towards fewer readers, not more.
UPDATE audit_log a
   SET classroom_id = c.classroom_id
  FROM classrooms c
 WHERE a.classroom_id IS NULL
   AND a.entity_type = 'CLASSROOM'
   AND split_part(a.entity_id, ':', 1) ~ '^[0-9]+$'
   AND c.classroom_id = split_part(a.entity_id, ':', 1)::BIGINT;

UPDATE audit_log a
   SET classroom_id = g.classroom_id
  FROM contest_groups g
 WHERE a.classroom_id IS NULL
   AND (a.entity_type = 'GROUP' OR a.action = 'ADMIN_GROUP_CONTEST_ADDED')
   AND split_part(a.entity_id, ':', 1) ~ '^[0-9]+$'
   AND g.group_id = split_part(a.entity_id, ':', 1)::BIGINT;

UPDATE audit_log a
   SET classroom_id = e.classroom_id
  FROM group_contests e
 WHERE a.classroom_id IS NULL
   AND a.entity_type IN ('EXAM', 'CONTEST')
   AND (a.action LIKE 'ADMIN_EVENT_%' OR a.action LIKE 'ADMIN_EXAM_%'
        OR a.action LIKE 'EXAM_%' OR a.action = 'ADMIN_GROUP_CONTEST_REMOVED')
   AND split_part(a.entity_id, ':', 1) ~ '^[0-9]+$'
   AND e.contest_id = split_part(a.entity_id, ':', 1)::BIGINT;
