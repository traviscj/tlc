CREATE TABLE approval_rule_checks (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 1,
  approval_rule_token VARCHAR(32) NOT NULL,
  name VARCHAR(96) NOT NULL,
  description VARCHAR(512) NULL,
  reporter VARCHAR(96) NULL,
  min_value DOUBLE NULL,
  max_value DOUBLE NULL,
  unit VARCHAR(48) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY u_rule_check (approval_rule_token, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
