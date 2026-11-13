ALTER TABLE approval_rules
  ADD COLUMN agent_approvals VARCHAR(8) NOT NULL DEFAULT 'DENY',
  ADD CONSTRAINT c_agent_approvals CHECK (agent_approvals IN ('DENY', 'ALLOW'))
