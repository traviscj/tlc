CREATE TABLE log_entries (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at_ms BIGINT NOT NULL,
  timestamp_ms BIGINT NOT NULL,
  level TINYINT NOT NULL,
  message TEXT NOT NULL,
  source VARCHAR(96) NOT NULL,
  external_entity_type_id BIGINT NULL,
  external_entity_id BIGINT NULL,
  PRIMARY KEY (id),
  KEY k_entity_ts (external_entity_id, timestamp_ms, id),
  KEY k_type_ts (external_entity_type_id, timestamp_ms, id),
  KEY k_ts (timestamp_ms, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
