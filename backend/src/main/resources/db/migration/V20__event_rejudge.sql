-- Rejudging one problem of an event. CPIntel sends every archived submission to that problem
-- to the judge again, in the order they were first sent, and re-ranks on the verdicts that come
-- back. It runs in the background — a room's worth of submissions takes the judge minutes — so
-- the event carries how the latest one is going: RUNNING, then DONE or FAILED, with a note
-- saying how many were sent and how many verdicts changed.
ALTER TABLE group_contests
    ADD COLUMN rejudge_status     VARCHAR(10),
    ADD COLUMN rejudge_label      VARCHAR(8),
    ADD COLUMN rejudge_started_at TIMESTAMPTZ,
    ADD COLUMN rejudge_note       VARCHAR(500);
