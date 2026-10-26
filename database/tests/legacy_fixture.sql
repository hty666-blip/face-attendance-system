-- 纯合成升级夹具，无真实账号、照片或凭据；故意不建立可登录账号。
INSERT INTO records(id,user_id,create_time,address,state_radio)
VALUES(90001,90001,'2024-01-02 08:01:00','synthetic migration address','正常');
INSERT INTO leaveapply(id,user_id,type_radio,periods,content,create_time,state_radio)
VALUES(90001,90001,'事假','legacy free text','synthetic migration reason','2024-01-02 09:00:00','审核中');
INSERT INTO face_checkin_session(id,user_id,provider,template_version,expires_at,state,record_id,created_at)
VALUES
('synthetic-pending',90001,'local',1,'2024-01-02 09:10:00','PENDING',NULL,'2024-01-02 09:00:00'),
('synthetic-processing',90001,'local',1,'2024-01-02 09:10:00','PROCESSING',NULL,'2024-01-02 09:00:00'),
('synthetic-succeeded',90001,'local',1,'2024-01-02 09:10:00','SUCCEEDED',90001,'2024-01-02 09:00:00');
