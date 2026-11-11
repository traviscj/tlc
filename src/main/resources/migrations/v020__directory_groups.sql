CREATE TABLE directory_groups (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 1,
  directory_group VARCHAR(96) NOT NULL,
  slack_channel VARCHAR(96) NULL,
  email VARCHAR(255) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY u_group (directory_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
