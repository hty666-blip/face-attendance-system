# MySQL 8.4 迁移验证

此目录只包含公开脱敏基线和合成数据。`v002_schema.sql`、`v002_seed.sql` 来源为本公开仓库 commit `382b95a0a20f72eb3396b45498249cd91bf02ba7` 的 `database/schema.sql`、`database/seed.sql`，冻结用于验证 V003，不能随当前结构一起更新。`legacy_fixture.sql` 不含可登录账号、真人数据或照片，不来源于毕设原始 SQL。

在项目根目录执行：

```text
bash database/tests/verify-migrations.sh <独立临时MySQL容器名或ID>
```

需要 Bash、Docker CLI 及已启动的 `mysql:8.4` 临时容器。脚本从容器现有环境读取 `MYSQL_ROOT_PASSWORD`，不在宿主机命令行传密码。只会新建 `attendance_verify`、`attendance_upgrade_verify` 两个固定库；任一已存在就拒绝，不删除或覆盖数据库、不启动或停止容器。失败后不要在同一容器重复执行，应由调用方清理该测试容器并新建独立容器。绝不对原库、共用数据库服务或生产容器执行。

覆盖新库结构与种子、V002→V003→V004 升级、旧事件/自由时长请假保留、不猜测日期动作、旧未完成会话失效、初始规则日期/时间、内置主管审批授权、MySQL 每日动作唯一约束、空导出任务表及内置员工/主管的受限导出授权。GitHub Actions 为其单独启动临时 MySQL 8.4 service，测试后自动清理，不挂载业务数据。
