CREATE TABLE approvable_parties (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at_ms BIGINT NOT NULL,
  approvable_id BIGINT NOT NULL,
  directory_group VARCHAR(96) NOT NULL,
  mode VARCHAR(8) NOT NULL,
  aspects VARCHAR(512) NOT NULL,
  status VARCHAR(16) NOT NULL,
  source VARCHAR(16) NOT NULL,
  via VARCHAR(1024) NOT NULL DEFAULT '',
  slack_channel VARCHAR(96) NULL,
  email VARCHAR(255) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY u_approvable_group (approvable_id, directory_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
