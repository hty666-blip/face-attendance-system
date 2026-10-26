CREATE TABLE IF NOT EXISTS `dept` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(200) DEFAULT NULL COMMENT '部门名称',
  `user_id` int(11) DEFAULT NULL COMMENT '部门主管',
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `dict` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '编号',
  `code` varchar(255) DEFAULT NULL COMMENT '编码',
  `value` varchar(255) DEFAULT NULL COMMENT '内容',
  `type` varchar(10) DEFAULT NULL COMMENT '类型',
  `deleted` int(11) DEFAULT '0' COMMENT '删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `c_d` (`code`,`deleted`)
);

CREATE TABLE IF NOT EXISTS `employee` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '编号',
  `username` varchar(200) DEFAULT NULL COMMENT '登录账户',
  `name` varchar(200) DEFAULT NULL COMMENT '员工姓名',
  `user_id` int(11) DEFAULT NULL COMMENT '所属用户',
  `dept_id` int(11) DEFAULT NULL COMMENT '所属部门',
  `phone` varchar(200) DEFAULT NULL COMMENT '手机号码',
  `title` varchar(200) DEFAULT NULL COMMENT '职位',
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `leaveapply` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '编号',
  `user_id` int(11) DEFAULT NULL COMMENT '请假员工',
  `type_radio` varchar(200) DEFAULT NULL COMMENT '类型,事假|病假|婚假|丧假',
  `periods` varchar(200) DEFAULT NULL COMMENT '请假时长',
  `content` text COMMENT '请假原因',
  `create_time` datetime DEFAULT NULL COMMENT '申请时间',
  `state_radio` varchar(200) DEFAULT '审核中' COMMENT '状态,审核中|通过|不通过',
  `remarks` varchar(200) DEFAULT NULL COMMENT '审核说明',
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `manager` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '编号',
  `username` varchar(200) DEFAULT NULL COMMENT '登录账户',
  `name` varchar(200) DEFAULT NULL COMMENT '主管姓名',
  `user_id` int(11) DEFAULT NULL COMMENT '所属用户',
  `phone` varchar(200) DEFAULT NULL COMMENT '手机号码',
  `dept_id` int(11) DEFAULT NULL COMMENT '所属部门',
  `title` varchar(200) DEFAULT NULL COMMENT '职位',
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `notice` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(255) DEFAULT NULL COMMENT '名称',
  `content` text COMMENT '内容',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `user_id` int(11) DEFAULT NULL COMMENT '创建人',
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `records` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '编号',
  `user_id` int(11) DEFAULT NULL COMMENT '考勤员工',
  `create_time` datetime DEFAULT NULL COMMENT '打卡时间',
  `address` varchar(200) DEFAULT NULL COMMENT '打卡地点',
  `state_radio` varchar(200) DEFAULT NULL COMMENT '状态,迟到|早退|正常',
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `rules` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(200) DEFAULT NULL COMMENT '规则名称',
  `time` varchar(200) DEFAULT NULL COMMENT '具体时间',
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `sys_permission` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(255) DEFAULT NULL COMMENT '名称',
  `path` varchar(255) DEFAULT NULL COMMENT '路径',
  `orders` int(11) DEFAULT '1' COMMENT '顺序',
  `icon` varchar(255) DEFAULT 'grid' COMMENT '图标',
  `page` varchar(255) DEFAULT NULL COMMENT '页面路径',
  `auth` varchar(255) DEFAULT NULL COMMENT '权限',
  `pid` int(11) DEFAULT NULL COMMENT '父级编号',
  `deleted` int(11) DEFAULT '0' COMMENT '逻辑删除',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `type` int(11) DEFAULT NULL COMMENT '类型',
  `hide` tinyint(1) DEFAULT '0' COMMENT '是否隐藏',
  PRIMARY KEY (`id`),
  UNIQUE KEY `a_d_index` (`auth`,`deleted`),
  UNIQUE KEY `p_p_d_index` (`path`,`page`,`deleted`)
);

CREATE TABLE IF NOT EXISTS `sys_role` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(255) DEFAULT NULL COMMENT '名称',
  `flag` varchar(255) DEFAULT NULL COMMENT '唯一标识',
  `deleted` int(11) DEFAULT '0' COMMENT '逻辑删除',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `flag_deleted_idnex` (`flag`,`deleted`)
);

CREATE TABLE IF NOT EXISTS `sys_role_permission` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '编号',
  `role_id` int(11) NOT NULL COMMENT '角色',
  `permission_id` int(11) NOT NULL COMMENT '权限',
  PRIMARY KEY (`id`),
  UNIQUE KEY `role_id` (`role_id`,`permission_id`)
);

CREATE TABLE IF NOT EXISTS `sys_user` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '编号',
  `username` varchar(255) NOT NULL COMMENT '用户名',
  `password` varchar(255) NOT NULL COMMENT '密码',
  `name` varchar(255) DEFAULT NULL COMMENT '昵称',
  `email` varchar(30) NOT NULL COMMENT '邮箱',
  `address` varchar(255) DEFAULT NULL COMMENT '地址',
  `uid` varchar(40) NOT NULL COMMENT '用户唯一id',
  `deleted` int(11) NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  `create_time` datetime DEFAULT NULL COMMENT '添加时间',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  `avatar` varchar(500) DEFAULT NULL COMMENT '头像',
  `role` varchar(255) DEFAULT NULL COMMENT '角色',
  `score` int(11) DEFAULT '0' COMMENT '积分',
  `face` longtext COMMENT '人脸数据',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uid_index` (`uid`),
  UNIQUE KEY `username_index` (`username`,`deleted`),
  UNIQUE KEY `email_index` (`email`,`deleted`)
);

-- 先备份旧库，再在选定的数据库副本执行；不自动批量转换或删除旧照片。
CREATE TABLE IF NOT EXISTS face_template (
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
);

CREATE TABLE IF NOT EXISTS face_checkin_session (
  id varchar(64) NOT NULL,
  user_id int NOT NULL,
  provider varchar(20) NOT NULL,
  template_version int NOT NULL,
  expires_at datetime NOT NULL,
  state varchar(20) NOT NULL,
  request_hash varchar(64),
  record_id int,
  created_at datetime NOT NULL,
  PRIMARY KEY (id)
);

-- V003 test schema
CREATE TABLE IF NOT EXISTS attendance_policy (
  id bigint NOT NULL AUTO_INCREMENT,
  effective_from date NOT NULL,
  start_time time NOT NULL,
  end_time time NOT NULL,
  grace_minutes int NOT NULL DEFAULT 0,
  created_by int NOT NULL,
  created_at datetime NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_attendance_policy_date (effective_from)
);

CREATE TABLE IF NOT EXISTS attendance_calendar (
  date date NOT NULL,
  working_day boolean NOT NULL,
  note varchar(200) NOT NULL,
  updated_by int NOT NULL,
  updated_at datetime NOT NULL,
  PRIMARY KEY (date)
);

CREATE TABLE IF NOT EXISTS attendance_day (
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
);

CREATE TABLE IF NOT EXISTS attendance_request (
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
  UNIQUE KEY uq_attendance_request_key (user_id,request_key)
);

CREATE TABLE IF NOT EXISTS attendance_approval (
  id bigint NOT NULL AUTO_INCREMENT,
  request_id bigint NOT NULL,
  actor_id int NOT NULL,
  from_state varchar(20),
  to_state varchar(20) NOT NULL,
  comment varchar(1000),
  created_at datetime NOT NULL,
  PRIMARY KEY (id)
);

ALTER TABLE records ADD COLUMN IF NOT EXISTS business_date date;
ALTER TABLE records ADD COLUMN IF NOT EXISTS action varchar(3);
ALTER TABLE records ADD COLUMN IF NOT EXISTS occurred_at datetime;
ALTER TABLE records ADD COLUMN IF NOT EXISTS source varchar(20);
ALTER TABLE records ADD COLUMN IF NOT EXISTS dept_id int;
ALTER TABLE records ADD COLUMN IF NOT EXISTS request_id bigint;
CREATE UNIQUE INDEX IF NOT EXISTS uq_attendance_event ON records(user_id,business_date,action);
CREATE UNIQUE INDEX IF NOT EXISTS uq_attendance_request_event ON records(request_id);
ALTER TABLE face_checkin_session ADD COLUMN IF NOT EXISTS business_date date;
ALTER TABLE face_checkin_session ADD COLUMN IF NOT EXISTS action varchar(3);

-- V004 test schema
-- 旧库先备份，执行 V001 至 V003 后执行一次；不读取或转换任何原始人脸资料。

CREATE TABLE IF NOT EXISTS attendance_export_job (
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
);
