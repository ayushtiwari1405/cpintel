-- The table ShedLock uses to elect one runner per scheduled job.
--
-- Spring's scheduler runs every @Scheduled method on every instance it is started on. With one
-- instance that is invisible; with two, the nightly analytics pass runs twice concurrently over
-- the same users and the three-minute standings refresh races itself writing the same group
-- rows. A row here is the claim: whoever inserts or updates it first for a given job name owns
-- that run, and the others skip.
--
-- Postgres rather than Redis because a missed lock has to be a correctness failure, not a cache
-- miss — and because the jobs cannot do anything useful without Postgres up anyway.

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);

COMMENT ON TABLE shedlock IS
  'One row per scheduled job. lock_until is when the claim lapses, so a runner that dies mid-job '
  'releases it without anyone intervening.';
