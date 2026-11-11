CREATE TABLE external_entities (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 1,
  token VARCHAR(32) NOT NULL,
  external_entity_type_id BIGINT NOT NULL,
  source_entity_id VARCHAR(191) NOT NULL,
  display_name VARCHAR(255) NULL,
  deleted_at_ms BIGINT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY u_token (token),
  UNIQUE KEY u_type_source (external_entity_type_id, source_entity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
