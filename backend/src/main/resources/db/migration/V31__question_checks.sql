-- The latest check of each bank question's model solution against its tests.
--
-- A check runs the model solution on every test, in the code runner, under the question's own
-- limits, and compares each output with the test's answer the way the judge will. It can also
-- first fill in missing answers from the model solution. A question becomes READY only when a
-- check of its current version passed; any later edit sends it back to DRAFT, and the check
-- row then describes an older version.
--
-- Checks run one at a time in the background. One row per question, overwritten by the next
-- check; the results are kept as JSON, read only by the editor.

CREATE TABLE bank_question_checks (
    question_id   BIGINT       PRIMARY KEY,
    -- The question's version the result describes. Generating answers creates a new version
    -- part way through, and this follows it.
    version       INTEGER      NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    generate      BOOLEAN      DEFAULT FALSE NOT NULL,
    started_by    BIGINT,
    started_at    TIMESTAMPTZ  DEFAULT now() NOT NULL,
    finished_at   TIMESTAMPTZ,
    -- One sentence for the editor: what passed, or the first thing that did not.
    summary       VARCHAR(1000),
    max_time_ms   INTEGER,
    results       TEXT,
    CONSTRAINT fk_bqc_question   FOREIGN KEY (question_id) REFERENCES bank_questions(question_id) ON DELETE CASCADE,
    CONSTRAINT fk_bqc_started_by FOREIGN KEY (started_by)  REFERENCES users(user_id)              ON DELETE SET NULL,
    CONSTRAINT ck_bqc_status     CHECK (status IN ('QUEUED', 'RUNNING', 'PASSED', 'FAILED', 'ERROR'))
);
