ALTER TABLE approvals
  ADD COLUMN revision_seq INT NOT NULL DEFAULT 1,
  ADD COLUMN revision VARCHAR(64) NULL,
  ADD COLUMN superseded_by_seq INT NULL
