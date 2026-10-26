-- 仅供新建空库；不含原始用户、口令、人脸与个人业务数据。
CREATE TABLE `dept` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(200) DEFAULT NULL COMMENT '部门名称',
  `user_id` int(11) DEFAULT NULL COMMENT '部门主管',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8 COMMENT='部门信息';

CREATE TABLE `dict` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '编号',
  `code` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '编码',
  `value` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '内容',
  `type` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '类型',
  `deleted` int(11) DEFAULT '0' COMMENT '删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `c_d` (`code`,`deleted`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=20 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='数据字典';

CREATE TABLE `employee` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '编号',
  `username` varchar(200) DEFAULT NULL COMMENT '登录账户',
  `name` varchar(200) DEFAULT NULL COMMENT '员工姓名',
  `user_id` int(11) DEFAULT NULL COMMENT '所属用户',
  `dept_id` int(11) DEFAULT NULL COMMENT '所属部门',
  `phone` varchar(200) DEFAULT NULL COMMENT '手机号码',
  `title` varchar(200) DEFAULT NULL COMMENT '职位',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8 COMMENT='员工信息';

CREATE TABLE `leaveapply` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '编号',
  `user_id` int(11) DEFAULT NULL COMMENT '请假员工',
  `type_radio` varchar(200) DEFAULT NULL COMMENT '类型,事假|病假|婚假|丧假',
  `periods` varchar(200) DEFAULT NULL COMMENT '请假时长',
  `content` text COMMENT '请假原因',
  `create_time` datetime DEFAULT NULL COMMENT '申请时间',
  `state_radio` varchar(200) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT '审核中' COMMENT '状态,审核中|通过|不通过',
  `remarks` varchar(200) DEFAULT NULL COMMENT '审核说明',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8 COMMENT='请假申请';

CREATE TABLE `manager` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '编号',
  `username` varchar(200) DEFAULT NULL COMMENT '登录账户',
  `name` varchar(200) DEFAULT NULL COMMENT '主管姓名',
  `user_id` int(11) DEFAULT NULL COMMENT '所属用户',
  `phone` varchar(200) DEFAULT NULL COMMENT '手机号码',
  `dept_id` int(11) DEFAULT NULL COMMENT '所属部门',
  `title` varchar(200) DEFAULT NULL COMMENT '职位',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8 COMMENT='部门主管';

CREATE TABLE `notice` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '名称',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '内容',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `user_id` int(11) DEFAULT NULL COMMENT '创建人',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=15 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='系统公告';

CREATE TABLE `records` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '编号',
  `user_id` int(11) DEFAULT NULL COMMENT '考勤员工',
  `create_time` datetime DEFAULT NULL COMMENT '打卡时间',
  `address` varchar(200) DEFAULT NULL COMMENT '打卡地点',
  `state_radio` varchar(200) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '状态,迟到|早退|正常',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=17 DEFAULT CHARSET=utf8 COMMENT='考勤记录';

CREATE TABLE `rules` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(200) DEFAULT NULL COMMENT '规则名称',
  `time` varchar(200) DEFAULT NULL COMMENT '具体时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8 COMMENT='考勤规则';

CREATE TABLE `sys_permission` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '名称',
  `path` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '路径',
  `orders` int(11) DEFAULT '1' COMMENT '顺序',
  `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'grid' COMMENT '图标',
  `page` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '页面路径',
  `auth` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '权限',
  `pid` int(11) DEFAULT NULL COMMENT '父级编号',
  `deleted` int(11) DEFAULT '0' COMMENT '逻辑删除',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `type` int(11) DEFAULT NULL COMMENT '类型',
  `hide` tinyint(1) DEFAULT '0' COMMENT '是否隐藏',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `a_d_index` (`auth`,`deleted`) USING BTREE,
  UNIQUE KEY `p_p_d_index` (`path`,`page`,`deleted`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=611 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='权限菜单';

CREATE TABLE `sys_role` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '名称',
  `flag` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '唯一标识',
  `deleted` int(11) DEFAULT '0' COMMENT '逻辑删除',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `flag_deleted_idnex` (`flag`,`deleted`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='角色';

CREATE TABLE `sys_role_permission` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '编号',
  `role_id` int(11) NOT NULL COMMENT '角色',
  `permission_id` int(11) NOT NULL COMMENT '权限',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `role_id` (`role_id`,`permission_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=6017 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='角色权限';

CREATE TABLE `sys_user` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '编号',
  `username` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户名',
  `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '密码',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '昵称',
  `email` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '邮箱',
  `address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '地址',
  `uid` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户唯一id',
  `deleted` int(11) NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  `create_time` datetime DEFAULT NULL COMMENT '添加时间',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  `avatar` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '头像',
  `role` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '角色',
  `score` int(11) DEFAULT '0' COMMENT '积分',
  `face` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '人脸数据',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uid_index` (`uid`) USING BTREE,
  UNIQUE KEY `username_index` (`username`,`deleted`) USING BTREE,
  UNIQUE KEY `email_index` (`email`,`deleted`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=28 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='用户';

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

-- V003 固定日班与申请审批
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
