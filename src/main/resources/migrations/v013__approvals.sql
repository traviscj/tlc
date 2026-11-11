CREATE TABLE approvals (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 1,
  token VARCHAR(32) NOT NULL,
  approvable_id BIGINT NOT NULL,
  approvable_token VARCHAR(32) NOT NULL,
  approved_by VARCHAR(96) NOT NULL,
  approved_at_ms BIGINT NOT NULL,
  unapproved_at_ms BIGINT NULL,
  via_groups VARCHAR(1024) NOT NULL DEFAULT '',
  comment VARCHAR(1024) NULL,
  active TINYINT GENERATED ALWAYS AS (IF(unapproved_at_ms IS NULL, 1, NULL)) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY u_token (token),
  UNIQUE KEY u_one_active (approvable_id, approved_by, active),
  KEY k_approver (approved_by, approved_at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
