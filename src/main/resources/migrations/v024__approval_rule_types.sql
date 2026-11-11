CREATE TABLE approval_rule_types (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at_ms BIGINT NOT NULL,
  approval_rule_token VARCHAR(32) NOT NULL,
  external_entity_type_id BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY u_rule_type (approval_rule_token, external_entity_type_id),
  KEY k_type (external_entity_type_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
