#!/usr/bin/env bash
# 只向明确指定的 MySQL 8.4 临时容器新建两个固定测试库；已有同名库时拒绝。
set -euo pipefail

if [[ $# != 1 || ! "$1" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]*$ ]]; then
  echo 'Usage: bash database/tests/verify-migrations.sh <ephemeral-mysql-container>' >&2
  exit 2
fi
container="$1"
root_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ "$(docker inspect --format '{{.State.Running}}' "$container")" != true ]]; then
  echo 'The specified container must already be running.' >&2
  exit 2
fi
case "$(docker inspect --format '{{.Config.Image}}' "$container")" in
  mysql:8.4|mysql:8.4.*|mysql:8.4@*) ;;
  *) echo 'Only an explicitly selected mysql:8.4 test container is supported.' >&2; exit 2 ;;
esac

# 密码从容器已存在的环境读取，不放进命令参数或日志。
mysql_in() {
  docker exec -i "$container" sh -c '
    : "${MYSQL_ROOT_PASSWORD:?Test container requires MYSQL_ROOT_PASSWORD}"
    export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
    exec mysql --protocol=tcp -h127.0.0.1 --connect-timeout=5 --default-character-set=utf8mb4 --batch --skip-column-names -uroot "$@"
  ' sh "$@"
}
assert_eq() {
  if [[ "$2" != "$3" ]]; then
    printf 'FAIL: %s (expected %s, got %s)\n' "$1" "$2" "$3" >&2
    exit 1
  fi
  printf 'PASS: %s\n' "$1"
}

for db in attendance_verify attendance_upgrade_verify; do
  assert_eq "target database $db does not exist" 0 "$(mysql_in -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$db'")"
done
# 不删除数据库、不启动容器；调用方负责使用和清理独立临时容器。
mysql_in -e 'CREATE DATABASE attendance_verify CHARACTER SET utf8mb4; CREATE DATABASE attendance_upgrade_verify CHARACTER SET utf8mb4;'

mysql_in attendance_verify < "$root_dir/database/schema.sql"
mysql_in attendance_verify < "$root_dir/database/seed.sql"
assert_eq 'new schema has 23 tables' 23 "$(mysql_in -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='attendance_verify'")"
assert_eq 'new schema contains no accounts' 0 "$(mysql_in attendance_verify -e 'SELECT COUNT(*) FROM sys_user')"
assert_eq 'new schema contains no attendance events' 0 "$(mysql_in attendance_verify -e 'SELECT COUNT(*) FROM records')"
assert_eq 'new initial policy uses Shanghai date and 08-18 shift' 1 "$(mysql_in attendance_verify -e "SET time_zone='+08:00'; SELECT COUNT(*) FROM attendance_policy WHERE effective_from=CURRENT_DATE() AND start_time='08:00:00' AND end_time='18:00:00' AND grace_minutes=0")"

mysql_in attendance_upgrade_verify < "$root_dir/database/tests/v002_schema.sql"
mysql_in attendance_upgrade_verify < "$root_dir/database/tests/v002_seed.sql"
mysql_in attendance_upgrade_verify < "$root_dir/database/tests/legacy_fixture.sql"
mysql_in attendance_upgrade_verify < "$root_dir/database/migrations/V003__attendance_workflow.sql"
assert_eq 'upgraded schema has 19 tables' 19 "$(mysql_in -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='attendance_upgrade_verify'")"
assert_eq 'legacy record is preserved without invented facts' 1 "$(mysql_in attendance_upgrade_verify -e "SELECT COUNT(*) FROM records WHERE id=90001 AND address='synthetic migration address' AND state_radio='正常' AND business_date IS NULL AND action IS NULL AND occurred_at IS NULL AND source IS NULL AND dept_id IS NULL AND request_id IS NULL")"
assert_eq 'legacy free-text leave is preserved' 1 "$(mysql_in attendance_upgrade_verify -e "SELECT COUNT(*) FROM leaveapply WHERE id=90001 AND periods='legacy free text' AND content='synthetic migration reason' AND state_radio='审核中'")"
assert_eq 'no invented dated requests' 0 "$(mysql_in attendance_upgrade_verify -e 'SELECT COUNT(*) FROM attendance_request')"
assert_eq 'no invented day snapshots' 0 "$(mysql_in attendance_upgrade_verify -e 'SELECT COUNT(*) FROM attendance_day')"
assert_eq 'old pending and processing sessions invalidated' 2 "$(mysql_in attendance_upgrade_verify -e "SELECT COUNT(*) FROM face_checkin_session WHERE id IN ('synthetic-pending','synthetic-processing') AND state='INVALIDATED' AND action IS NULL AND business_date IS NULL")"
assert_eq 'successful old session preserved' 1 "$(mysql_in attendance_upgrade_verify -e "SELECT COUNT(*) FROM face_checkin_session WHERE id='synthetic-succeeded' AND state='SUCCEEDED' AND record_id=90001")"
assert_eq 'upgraded policy copies original shift on Shanghai date' 1 "$(mysql_in attendance_upgrade_verify -e "SET time_zone='+08:00'; SELECT COUNT(*) FROM attendance_policy WHERE effective_from=CURRENT_DATE() AND start_time='08:00:00' AND end_time='18:00:00' AND created_by=0")"
for db in attendance_verify attendance_upgrade_verify; do
  assert_eq "$db grants built-in manager correction review once" 1 "$(mysql_in "$db" -e "SELECT COUNT(*) FROM sys_role_permission rp JOIN sys_role r ON r.id=rp.role_id JOIN sys_permission p ON p.id=rp.permission_id WHERE r.flag='manager' AND r.deleted=0 AND p.auth='records.edit' AND p.deleted=0 AND p.name='补卡申请审批'")"
  assert_eq "$db has six event fact columns" 6 "$(mysql_in -e "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$db' AND table_name='records' AND column_name IN ('business_date','action','occurred_at','source','dept_id','request_id')")"
done

mysql_in attendance_verify -e "INSERT INTO records(user_id,business_date,action,occurred_at,source) VALUES(90002,'2024-01-02','IN','2024-01-02 08:00:00','CORRECTION')"
if mysql_in attendance_verify -e "INSERT INTO records(user_id,business_date,action,occurred_at,source) VALUES(90002,'2024-01-02','IN','2024-01-02 08:01:00','FACE')" 2>/dev/null; then
  echo 'FAIL: duplicate daily action was accepted' >&2
  exit 1
fi
assert_eq 'duplicate daily action rejected by MySQL' 1 "$(mysql_in attendance_verify -e 'SELECT COUNT(*) FROM records WHERE user_id=90002')"
mysql_in attendance_upgrade_verify < "$root_dir/database/migrations/V004__attendance_exports.sql"
assert_eq 'V004 upgraded schema has 20 tables' 20 "$(mysql_in -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='attendance_upgrade_verify'")"
for db in attendance_verify attendance_upgrade_verify; do
  assert_eq "$db has no invented export tasks" 0 "$(mysql_in "$db" -e 'SELECT COUNT(*) FROM attendance_export_job')"
  assert_eq "$db grants built-in employee and manager scoped export" 2 "$(mysql_in "$db" -e "SELECT COUNT(*) FROM sys_role_permission rp JOIN sys_role r ON r.id=rp.role_id JOIN sys_permission p ON p.id=rp.permission_id WHERE r.flag IN ('employee','manager') AND r.deleted=0 AND p.auth='records.export' AND p.deleted=0")"
  mysql_in "$db" -e "INSERT INTO attendance_export_job(owner_id,request_key,request_hash,month,scope,scope_signature,state,created_at) VALUES(90003,'synthetic-migration-export-key',REPEAT('a',64),'2024-01','SELF',REPEAT('b',64),'CANCELLED',NOW())"
  if mysql_in "$db" -e "INSERT INTO attendance_export_job(owner_id,request_key,request_hash,month,scope,scope_signature,state,created_at) VALUES(90003,'synthetic-migration-export-key',REPEAT('a',64),'2024-01','SELF',REPEAT('b',64),'CANCELLED',NOW())" 2>/dev/null; then
    echo "FAIL: $db accepted duplicate owner/request key" >&2
    exit 1
  fi
  mysql_in "$db" -e "INSERT INTO attendance_export_job(owner_id,request_key,request_hash,month,scope,scope_signature,state,created_at) VALUES(90004,'synthetic-migration-export-key',REPEAT('a',64),'2024-01','SELF',REPEAT('b',64),'CANCELLED',NOW())"
  assert_eq "$db export request key is unique per owner" 2 "$(mysql_in "$db" -e "SELECT COUNT(*) FROM attendance_export_job WHERE request_key='synthetic-migration-export-key'")"
done
assert_eq 'V004 preserves legacy event and new action fields' 1 "$(mysql_in attendance_upgrade_verify -e "SELECT COUNT(*) FROM records WHERE id=90001 AND address='synthetic migration address' AND business_date IS NULL AND action IS NULL")"
assert_eq 'V004 preserves initial policy' 1 "$(mysql_in attendance_upgrade_verify -e "SELECT COUNT(*) FROM attendance_policy WHERE start_time='08:00:00' AND end_time='18:00:00'")"
mysql_in attendance_upgrade_verify < "$root_dir/database/migrations/V005__assistant.sql"
assert_eq 'V005 upgraded schema has 23 tables' 23 "$(mysql_in -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='attendance_upgrade_verify'")"
for db in attendance_verify attendance_upgrade_verify; do
  for table in assistant_conversation assistant_run assistant_tool_call; do
    assert_eq "$db starts with no invented $table data" 0 "$(mysql_in "$db" -e "SELECT COUNT(*) FROM $table")"
  done
  assert_eq "$db assistant run request is unique per owner" 1 "$(mysql_in -e "SELECT COUNT(*) FROM (SELECT index_name FROM information_schema.statistics WHERE table_schema='$db' AND table_name='assistant_run' AND non_unique=0 GROUP BY index_name HAVING GROUP_CONCAT(column_name ORDER BY seq_in_index)='owner_id,request_key') AS owner_request_indexes")"
  assert_eq "$db preserves prior export metadata" 2 "$(mysql_in "$db" -e "SELECT COUNT(*) FROM attendance_export_job WHERE request_key='synthetic-migration-export-key' AND state='CANCELLED'")"
done
assert_eq 'V005 preserves legacy events' 1 "$(mysql_in attendance_upgrade_verify -e "SELECT COUNT(*) FROM records WHERE id=90001 AND business_date IS NULL AND action IS NULL")"
echo 'MySQL 8.4 new-schema and V002-to-V005 migration checks passed.'
