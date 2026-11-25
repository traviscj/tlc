CREATE TABLE item_events (
  id BIGINT NOT NULL AUTO_INCREMENT,
  at_ms BIGINT NOT NULL,
  item_id BIGINT NOT NULL,
  kind VARCHAR(16) NOT NULL,
  actor VARCHAR(96) NOT NULL,
  surface VARCHAR(32) NULL,
  other_item_id BIGINT NULL,
  detail VARCHAR(500) NULL,
  PRIMARY KEY (id),
  KEY k_item (item_id, id),
  KEY k_kind_at (kind, at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
