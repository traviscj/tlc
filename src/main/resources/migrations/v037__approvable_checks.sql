CREATE TABLE approvable_checks (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at_ms BIGINT NOT NULL,
  approvable_id BIGINT NOT NULL,
  revision_seq INT NOT NULL,
  revision VARCHAR(64) NULL,
  name VARCHAR(96) NOT NULL,
  status VARCHAR(8) NOT NULL,
  value DOUBLE NULL,
  summary VARCHAR(512) NULL,
  url VARCHAR(512) NULL,
  reported_by VARCHAR(96) NOT NULL,
  PRIMARY KEY (id),
  KEY k_approvable_check (approvable_id, revision_seq, name, id),
  CONSTRAINT c_check_status CHECK (status IN ('PENDING', 'PASS', 'FAIL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
