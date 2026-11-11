CREATE TABLE external_entity_types (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 1,
  token VARCHAR(32) NOT NULL,
  source_service VARCHAR(96) NOT NULL,
  source_entity VARCHAR(96) NOT NULL,
  description TEXT NULL,
  url_template VARCHAR(512) NULL,
  deleted_at_ms BIGINT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY u_token (token),
  UNIQUE KEY u_service_entity (source_service, source_entity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
