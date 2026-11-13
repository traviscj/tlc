ALTER TABLE approval_rules
  ADD COLUMN on_revision VARCHAR(8) NOT NULL DEFAULT 'DISMISS',
  ADD CONSTRAINT c_on_revision CHECK (on_revision IN ('DISMISS', 'KEEP'))
