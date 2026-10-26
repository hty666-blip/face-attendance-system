-- V005 AI助手：只保存本人会话、事实回执和有界运行；不迁移任何人脸或私人资料。
-- 旧库备份后，在V004之后执行一次。MySQL DDL隐式提交，回滚需恢复备份。
SET time_zone = '+08:00';
CREATE TABLE assistant_conversation (
  id bigint NOT NULL AUTO_INCREMENT,
  owner_id int NOT NULL,
  title varchar(100) NOT NULL,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  PRIMARY KEY (id),
  KEY ix_assistant_conversation_owner (owner_id,updated_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE assistant_run (
  id varchar(36) NOT NULL,
  conversation_id bigint NOT NULL,
  turn_index bigint NOT NULL,
  owner_id int NOT NULL,
  request_key varchar(64) NOT NULL,
  request_hash varchar(64) NOT NULL,
  message varchar(2000) NOT NULL,
  answer text,
  state varchar(20) NOT NULL,
  mode varchar(10) NOT NULL,
  error_code varchar(50),
  created_at datetime NOT NULL,
  finished_at datetime,
  deadline_at datetime NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_assistant_run_request (owner_id,request_key),
  UNIQUE KEY uq_assistant_run_turn (conversation_id,turn_index),
  KEY ix_assistant_run_owner (owner_id,state,deadline_at),
  KEY ix_assistant_run_conversation (conversation_id,turn_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE assistant_tool_call (
  id bigint NOT NULL AUTO_INCREMENT,
  run_id varchar(36) NOT NULL,
  call_order int NOT NULL,
  tool_name varchar(80) NOT NULL,
  arguments_json text NOT NULL,
  state varchar(20) NOT NULL,
  result_json mediumtext,
  error_code varchar(50),
  created_at datetime NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_assistant_tool_order (run_id,call_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
