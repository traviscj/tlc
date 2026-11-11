CREATE TABLE directory_memberships (
  id BIGINT NOT NULL AUTO_INCREMENT,
  created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 1,
  directory_group VARCHAR(96) NOT NULL,
  ldap VARCHAR(96) NOT NULL,
  is_admin TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY u_group_ldap (directory_group, ldap),
  KEY k_ldap (ldap)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
