-- 旧库先备份，执行 V001 至 V003 后执行一次；不读取或转换任何原始人脸资料。
SET time_zone = '+08:00';
CREATE TABLE attendance_export_job (
  id bigint NOT NULL AUTO_INCREMENT,
  owner_id int NOT NULL,
  request_key varchar(64) NOT NULL,
  request_hash varchar(64) NOT NULL,
  month varchar(7) NOT NULL,
  scope varchar(20) NOT NULL,
  dept_id int,
  scope_signature varchar(64) NOT NULL,
  access_signature varchar(64),
  state varchar(20) NOT NULL,
  attempts int NOT NULL DEFAULT 0,
  lease_token varchar(64),
  lease_until datetime,
  created_at datetime NOT NULL,
  started_at datetime,
  finished_at datetime,
  expires_at datetime,
  error_code varchar(50),
  error_message varchar(300),
  file_content mediumblob,
  PRIMARY KEY (id),
  UNIQUE KEY uq_attendance_export_request (owner_id,request_key),
  KEY ix_attendance_export_lease (state,lease_until),
  KEY ix_attendance_export_owner (owner_id,id),
  KEY ix_attendance_export_expiry (state,expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 内置角色获得受限导出权限，实际本人/部门范围仍由服务端校验。
INSERT INTO sys_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM sys_role r JOIN sys_permission p ON p.auth='records.export' AND p.deleted=0
WHERE r.flag IN ('employee','manager') AND r.deleted=0
AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
