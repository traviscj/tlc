CREATE TABLE approval_rules (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 1,
  token VARCHAR(32) NOT NULL,
  description TEXT NOT NULL,
  requirement VARCHAR(32) NOT NULL,
  min_groups INT NULL,
  created_by VARCHAR(96) NOT NULL,
  deleted_at_ms BIGINT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY u_token (token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
