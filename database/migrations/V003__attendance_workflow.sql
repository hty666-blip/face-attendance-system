-- 旧库先备份，在MySQL 8副本验证；执行V001、V002后执行一次。本脚本不猜测旧打卡动作或旧请假日期。
SET time_zone = '+08:00';

CREATE TABLE attendance_policy (
  id bigint NOT NULL AUTO_INCREMENT,
  effective_from date NOT NULL,
  start_time time NOT NULL,
  end_time time NOT NULL,
  grace_minutes int NOT NULL DEFAULT 0,
  created_by int NOT NULL,
  created_at datetime NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_attendance_policy_date (effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE attendance_calendar (
  date date NOT NULL,
  working_day boolean NOT NULL,
  note varchar(200) NOT NULL,
  updated_by int NOT NULL,
  updated_at datetime NOT NULL,
  PRIMARY KEY (date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE attendance_day (
  id bigint NOT NULL AUTO_INCREMENT,
  user_id int NOT NULL,
  business_date date NOT NULL,
  dept_id int NOT NULL DEFAULT 0,
  working_day boolean NOT NULL,
  policy_id bigint NOT NULL,
  start_time time NOT NULL,
  end_time time NOT NULL,
  grace_minutes int NOT NULL,
  created_at datetime NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_attendance_day (user_id,business_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE attendance_request (
  id bigint NOT NULL AUTO_INCREMENT,
  user_id int NOT NULL,
  dept_id int NOT NULL DEFAULT 0,
  request_key varchar(64) NOT NULL,
  request_hash varchar(64) NOT NULL,
  kind varchar(20) NOT NULL,
  business_date date,
  action varchar(3),
  correction_time datetime,
  start_date date,
  start_half varchar(2),
  end_date date,
  end_half varchar(2),
  leave_type varchar(20),
  reason varchar(1000) NOT NULL,
  state varchar(20) NOT NULL,
  version int NOT NULL,
  decision_by int,
  decision_at datetime,
  decision_comment varchar(500),
  created_at datetime NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_attendance_request_key (user_id,request_key),
  KEY ix_attendance_request_scope (dept_id,state,user_id),
  KEY ix_attendance_request_leave (user_id,kind,start_date,end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE attendance_approval (
  id bigint NOT NULL AUTO_INCREMENT,
  request_id bigint NOT NULL,
  actor_id int NOT NULL,
  from_state varchar(20),
  to_state varchar(20) NOT NULL,
  comment varchar(1000),
  created_at datetime NOT NULL,
  PRIMARY KEY (id),
  KEY ix_attendance_approval_request (request_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE records
  ADD COLUMN business_date date,
  ADD COLUMN action varchar(3),
  ADD COLUMN occurred_at datetime,
  ADD COLUMN source varchar(20),
  ADD COLUMN dept_id int,
  ADD COLUMN request_id bigint,
  ADD UNIQUE KEY uq_attendance_event (user_id,business_date,action),
  ADD UNIQUE KEY uq_attendance_request_event (request_id);

ALTER TABLE face_checkin_session ADD COLUMN business_date date, ADD COLUMN action varchar(3);
UPDATE face_checkin_session SET state='INVALIDATED' WHERE state IN ('PENDING','PROCESSING');

-- 部署当日作为新口径边界，原历史数据不回填；后续规则只能通过业务接口追加。
INSERT INTO attendance_policy(effective_from,start_time,end_time,grace_minutes,created_by,created_at)
SELECT CURRENT_DATE(),
  CAST(COALESCE(MAX(CASE WHEN name='上班时间' THEN time END),'08:00') AS TIME),
  CAST(COALESCE(MAX(CASE WHEN name='下班时间' THEN time END),'18:00') AS TIME),
  0,0,CURRENT_TIMESTAMP() FROM rules;

-- records.edit在新口径仅用于补卡审批，原始事件改写入口已关闭。
UPDATE sys_permission SET name='补卡申请审批' WHERE auth='records.edit' AND deleted=0;
INSERT INTO sys_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM sys_role r JOIN sys_permission p ON p.auth='records.edit' AND p.deleted=0
WHERE r.flag='manager' AND r.deleted=0
AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
