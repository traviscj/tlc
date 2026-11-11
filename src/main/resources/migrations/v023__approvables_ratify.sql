ALTER TABLE approvables
  ADD COLUMN mode VARCHAR(8) NOT NULL DEFAULT 'APPROVE',
  ADD COLUMN expires_at_ms BIGINT NULL,
  ADD COLUMN acting_group VARCHAR(96) NULL,
  DROP CHECK c_state,
  ADD CONSTRAINT c_state2 CHECK (approval_state IN ('PENDING', 'APPROVED', 'EXECUTED', 'DECLINED', 'ACTED', 'RATIFIED', 'REVERTED')),
  ADD CONSTRAINT c_mode CHECK (mode IN ('APPROVE', 'RATIFY')),
  ADD KEY k_expires (approval_state, expires_at_ms)
