ALTER TABLE items
  ADD COLUMN feed_sync_id BIGINT NULL,
  ADD COLUMN shard INT NOT NULL DEFAULT 0,
  ADD UNIQUE KEY u_fsi (feed_sync_id)
