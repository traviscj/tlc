CREATE TABLE approval_rule_groups (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 1,
  approval_rule_token VARCHAR(32) NOT NULL,
  group_name VARCHAR(96) NOT NULL,
  directory_group VARCHAR(96) NOT NULL,
  admins_only TINYINT(1) NOT NULL DEFAULT 0,
  slack_channel VARCHAR(96) NULL,
  email VARCHAR(255) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY u_rule_group (approval_rule_token, group_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
