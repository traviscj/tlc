CREATE TABLE approvable_revisions (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at_ms BIGINT NOT NULL,
  approvable_id BIGINT NOT NULL,
  seq INT NOT NULL,
  revision VARCHAR(64) NULL,
  context TEXT NULL,
  revised_by VARCHAR(96) NOT NULL,
  via_service VARCHAR(96) NULL,
  dismissed INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY u_approvable_seq (approvable_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
