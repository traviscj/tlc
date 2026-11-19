CREATE TABLE feed_cursors (
  id BIGINT NOT NULL AUTO_INCREMENT,
  consumer VARCHAR(255) NOT NULL,
  feed VARCHAR(64) NOT NULL,
  shard INT NOT NULL DEFAULT 0,
  shard_count INT NOT NULL DEFAULT 1,
  token VARCHAR(255) NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  last_advanced_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY u_consumer_feed_shard (consumer, feed, shard)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
