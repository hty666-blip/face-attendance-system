-- 仅在已经备份的旧库副本上执行。DDL存在隐式提交，不宣称可以事务回滚。
-- 先核对当前选中的数据库；不包含DROP、DELETE或清空业务数据。
ALTER TABLE dept ENGINE=InnoDB;
ALTER TABLE employee ENGINE=InnoDB;
ALTER TABLE manager ENGINE=InnoDB;
ALTER TABLE leaveapply ENGINE=InnoDB;
ALTER TABLE records ENGINE=InnoDB;
ALTER TABLE rules ENGINE=InnoDB;
UPDATE sys_permission SET pid = NULL WHERE pid = 0 OR pid = id;
UPDATE sys_permission SET deleted = id WHERE page = 'Dashboard';
