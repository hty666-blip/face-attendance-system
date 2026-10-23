-- 先备份旧库，再在选定的数据库副本执行；不自动批量转换或删除旧照片。
CREATE TABLE face_template (
  id bigint NOT NULL AUTO_INCREMENT,
  user_id int NOT NULL,
  provider varchar(20) NOT NULL,
  model_version varchar(100) NOT NULL,
  encrypted_payload longtext,
  version int NOT NULL,
  active boolean NOT NULL,
  updated_by int NOT NULL,
  updated_at datetime NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_face_user_provider (user_id, provider)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE face_checkin_session (
  id varchar(64) NOT NULL,
  user_id int NOT NULL,
  provider varchar(20) NOT NULL,
  template_version int NOT NULL,
  expires_at datetime NOT NULL,
  state varchar(20) NOT NULL,
  request_hash varchar(64),
  record_id int,
  created_at datetime NOT NULL,
  PRIMARY KEY (id),
  KEY ix_face_session_user_expiry (user_id, expires_at),
  KEY ix_face_session_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
