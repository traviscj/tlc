CREATE TABLE likes (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 1,
  token VARCHAR(32) NOT NULL,
  comment_id BIGINT NOT NULL,
  liked_by VARCHAR(96) NOT NULL,
  liked_at_ms BIGINT NOT NULL,
  unliked_at_ms BIGINT NULL,
  active TINYINT GENERATED ALWAYS AS (IF(unliked_at_ms IS NULL, 1, NULL)) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY u_token (token),
  UNIQUE KEY u_one_active (comment_id, liked_by, active),
  KEY k_liker (liked_by, liked_at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
