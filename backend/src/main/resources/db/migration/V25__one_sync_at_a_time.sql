-- At most one sync in flight per linked account.
--
-- Two syncs of one account (a double click, two tabs, the nightly job meeting a manual one)
-- paged the same history at the same time; the unique index on the archive kept the data right,
-- but the slower one failed on a duplicate key and its job read FAILED for no reason anybody
-- could see. The service refuses a second one with a message; this index is what holds when two
-- requests arrive in the same instant.
--
-- Jobs still marked in flight now were left by a backend that has since stopped — this
-- migration runs as the new one starts — so nothing is working on them.
UPDATE sync_jobs
   SET status = 'FAILED', error_msg = 'Interrupted by a restart', completed_at = now()
 WHERE status IN ('QUEUED', 'RUNNING');

CREATE UNIQUE INDEX uq_sync_jobs_in_flight ON sync_jobs (user_id, platform)
    WHERE status IN ('QUEUED', 'RUNNING');
