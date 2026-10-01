-- Closing an event for good. Once it is over and any re-evaluation on the judge has finished,
-- an admin marks it done: the verdicts are read from the judge one last time, the leaderboard
-- is fixed as it then stands, and everything — every submission, filed under the username of
-- whoever sent it, and the leaderboard as a spreadsheet — is packed into one zip for download.
--
-- The zip itself lives in MongoDB (GridFS), beside the submissions it was built from; this row
-- holds where it is and how the build went. It is built in the background, because reading two
-- hundred people's verdicts back from a judge outlasts a request.
ALTER TABLE group_contests
    ADD COLUMN completed_at      TIMESTAMPTZ,
    ADD COLUMN completed_by      BIGINT,
    ADD COLUMN export_status     VARCHAR(10),
    ADD COLUMN export_started_at TIMESTAMPTZ,
    ADD COLUMN export_file_id    VARCHAR(64),
    ADD COLUMN export_bytes      BIGINT,
    ADD COLUMN export_note       VARCHAR(500);

ALTER TABLE group_contests
    ADD CONSTRAINT fk_gc_completed_by FOREIGN KEY (completed_by)
        REFERENCES users (user_id) ON DELETE SET NULL;
