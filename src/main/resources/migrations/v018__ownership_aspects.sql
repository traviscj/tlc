CREATE TABLE ownership_aspects (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 1,
  external_entity_type_id BIGINT NOT NULL,
  aspect VARCHAR(96) NOT NULL,
  description VARCHAR(512) NULL,
  created_by VARCHAR(96) NOT NULL,
  deleted_at_ms BIGINT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY u_type_aspect (external_entity_type_id, aspect)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
