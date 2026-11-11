CREATE TABLE entity_edges (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 1,
  token VARCHAR(32) NOT NULL,
  from_entity_id BIGINT NOT NULL,
  to_entity_id BIGINT NOT NULL,
  kind VARCHAR(32) NOT NULL,
  created_by VARCHAR(96) NOT NULL,
  note VARCHAR(512) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY u_token (token),
  UNIQUE KEY u_edge (from_entity_id, to_entity_id, kind),
  KEY k_to (to_entity_id),
  CONSTRAINT c_edge_no_self CHECK (from_entity_id <> to_entity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
