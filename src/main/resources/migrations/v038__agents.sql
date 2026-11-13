CREATE TABLE agents (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 1,
  name VARCHAR(96) NOT NULL,
  operator VARCHAR(96) NULL,
  description VARCHAR(512) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY u_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
