CREATE TABLE tags (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 1,
  token VARCHAR(32) NOT NULL,
  tag_name VARCHAR(96) NOT NULL,
  description TEXT NULL,
  created_by VARCHAR(96) NOT NULL,
  effective_at_ms BIGINT NOT NULL,
  ineffective_at_ms BIGINT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY u_token (token),
  UNIQUE KEY u_tag_name (tag_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
