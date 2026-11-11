CREATE TABLE approvable_impacts (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at_ms BIGINT NOT NULL,
  approvable_id BIGINT NOT NULL,
  aspect VARCHAR(96) NOT NULL,
  declared BOOLEAN NOT NULL,
  magnitude DOUBLE NULL,
  unit VARCHAR(48) NULL,
  note VARCHAR(512) NULL,
  rule_mode VARCHAR(8) NULL,
  threshold DOUBLE NULL,
  effect VARCHAR(8) NOT NULL,
  reason VARCHAR(255) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY u_approvable_aspect (approvable_id, aspect)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
