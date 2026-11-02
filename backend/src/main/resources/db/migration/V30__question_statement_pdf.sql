-- A question's statement can be the setter's own PDF instead of the Markdown CPIntel renders.
--
-- Some setters already have a statement typeset elsewhere. Uploading it switches the question
-- to that file; the Markdown is kept, so switching back loses nothing. The bytes sit in their
-- own table so that reading a question never loads them.

ALTER TABLE bank_questions ADD COLUMN statement_kind VARCHAR(16) DEFAULT 'MARKDOWN' NOT NULL;
ALTER TABLE bank_questions ADD CONSTRAINT ck_bq_statement_kind
    CHECK (statement_kind IN ('MARKDOWN', 'PDF'));

CREATE TABLE bank_question_statement_pdfs (
    question_id  BIGINT        PRIMARY KEY,
    pdf          BYTEA         NOT NULL,
    file_name    VARCHAR(200)  NOT NULL,
    size_bytes   INTEGER       NOT NULL,
    pages        INTEGER       NOT NULL,
    uploaded_by  BIGINT,
    uploaded_at  TIMESTAMPTZ   DEFAULT now() NOT NULL,
    CONSTRAINT fk_bqsp_question    FOREIGN KEY (question_id) REFERENCES bank_questions(question_id) ON DELETE CASCADE,
    CONSTRAINT fk_bqsp_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES users(user_id)              ON DELETE SET NULL
);
