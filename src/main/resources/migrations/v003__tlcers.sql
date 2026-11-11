CREATE TABLE tlcers (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 1,
  token VARCHAR(32) NOT NULL,
  ldap VARCHAR(96) NOT NULL,
  display_name VARCHAR(255) NULL,
  last_seen_at_ms BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY u_token (token),
  UNIQUE KEY u_ldap (ldap)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
