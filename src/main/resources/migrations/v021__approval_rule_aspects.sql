CREATE TABLE approval_rule_aspects (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 1,
  approval_rule_token VARCHAR(32) NOT NULL,
  aspect VARCHAR(96) NOT NULL,
  mode VARCHAR(8) NOT NULL,
  scope VARCHAR(12) NOT NULL DEFAULT 'TARGET',
  fallback_group VARCHAR(96) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY u_rule_aspect (approval_rule_token, aspect),
  CONSTRAINT c_ra_mode CHECK (mode IN ('CONSENT', 'NOTIFY')),
  CONSTRAINT c_ra_scope CHECK (scope IN ('TARGET', 'DOWNSTREAM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
