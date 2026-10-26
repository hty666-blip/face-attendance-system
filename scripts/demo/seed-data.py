"""仅初始化本项目独立临时演示库；业务事件必须由真实申请/审批 API 产生。"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
from datetime import datetime, timedelta, timezone

import httpx

PROJECT_ROOT = Path(__file__).resolve().parents[2]
PROJECT_LABEL = 'com.face-attendance.demo.project'
RUN_LABEL = 'com.face-attendance.demo.run'
FORMAT = 'attendance-demo-v1'
SHANGHAI = timezone(timedelta(hours=8))


class DemoError(Exception):
    """只携带固定脱敏错误，不包含外部 stdout、stderr 或请求正文。"""


def require(condition, message):
    if not condition:
        raise DemoError(message)


def positive_id(value):
    require(type(value) is int and 0 < value <= 2_147_483_647, '演示业务编号无效。')
    return value


def normalized(path):
    return os.path.normcase(os.path.abspath(str(path)))


def no_reparse(path):
    """在 resolve 前逐级检查，拒绝 symlink、junction 等重解析路径。"""
    current = Path(path.anchor)
    for part in path.parts[1:]:
        current /= part
        if not current.exists() and not current.is_symlink():
            continue
        metadata = current.lstat()
        require(not stat.S_ISLNK(metadata.st_mode) and not
                (getattr(metadata, 'st_file_attributes', 0) & getattr(stat, 'FILE_ATTRIBUTE_REPARSE_POINT', 0x400)),
                '演示路径包含重解析链接，已拒绝。')


def read_json(path):
    require(path.is_file() and path.stat().st_size <= 128 * 1024, '演示资料缺失或大小无效。')
    try:
        return json.loads(path.read_text(encoding='utf-8-sig'))
    except (ValueError, UnicodeError, OSError):
        raise DemoError('演示资料格式无效。') from None


def load_manifest(manifest_path):
    path = Path(manifest_path)
    require(path.is_absolute(), 'manifest 必须使用绝对路径。')
    require(path.name == 'manifest.json' and re.fullmatch(r'[a-f0-9]{32}', path.parent.name), '演示运行路径格式无效。')
    expected = PROJECT_ROOT / '.local' / 'demo' / path.parent.name / 'manifest.json'
    require(normalized(path) == normalized(expected), 'manifest 不在本项目演示目录内。')
    no_reparse(path)
    body = read_json(path)
    require(isinstance(body, dict) and body.get('formatVersion') == FORMAT, 'manifest 版本无效。')
    require(body.get('runId') == path.parent.name and normalized(body.get('projectRoot', '')) == normalized(PROJECT_ROOT), 'manifest 项目或运行编号不匹配。')
    require(isinstance(body.get('dockerContext'), str) and re.fullmatch(r'[A-Za-z0-9_.-]{1,100}', body['dockerContext']), 'Docker context 无效。')
    require(isinstance(body.get('containerId'), str) and re.fullmatch(r'[a-f0-9]{64}', body['containerId']), '容器不可变编号无效。')
    require(body.get('containerName') == 'attendance-demo-' + body['runId'], '演示容器名称无效。')
    ports = body.get('ports', {})
    require(isinstance(ports, dict) and all(type(ports.get(key)) is int and 1024 <= ports[key] <= 65535
            for key in ('database', 'backend', 'assistant', 'frontend')), '演示端口无效。')
    require(len({ports[key] for key in ('database', 'backend', 'assistant', 'frontend')}) == 4, '演示端口不能重复。')
    database, bootstrap = body.get('database', {}), body.get('bootstrap', {})
    require(database.get('name') == 'attendance_demo' and database.get('username') == 'root', '只允许固定演示数据库。')
    for value in (database.get('password'), bootstrap.get('password')):
        require(isinstance(value, str) and 32 <= len(value) <= 64 and re.fullmatch(r'[\x21-\x7e]+', value), '演示随机凭据无效。')
    require(isinstance(bootstrap.get('username'), str) and re.fullmatch(r'[A-Za-z][A-Za-z0-9_]{2,31}', bootstrap['username']), '演示管理员账号无效。')
    require(not body.get('ended', False), '本轮演示已经结束，不能重新初始化。')
    return path, body


class DemoDatabase:
    def __init__(self, manifest, runner=None):
        self.manifest = manifest
        self.runner = runner or subprocess.run

    def command(self, arguments, input_text=None):
        # --context 显式指定目标，并清掉可能改写 Docker 连接的父环境。
        environment = {key: value for key, value in os.environ.items()
                       if key.upper() not in ('DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH')}
        try:
            result = self.runner(['docker', '--context', self.manifest['dockerContext'], *arguments],
                                 input=input_text, encoding='utf-8', errors='strict', capture_output=True,
                                 check=False, timeout=45, env=environment)
        except (OSError, subprocess.SubprocessError, UnicodeError):
            raise DemoError('演示 Docker 操作失败，请查看本轮受保护日志。') from None
        require(result.returncode == 0, '演示 Docker 操作失败，请查看本轮受保护日志。')
        require(len(result.stdout) <= 256 * 1024, 'Docker 响应大小无效。')
        return result.stdout.strip()

    def validate(self):
        try:
            contexts = json.loads(self.command(['context', 'inspect', self.manifest['dockerContext']]))
            require(isinstance(contexts, list) and len(contexts) == 1, 'Docker context 响应无效。')
            endpoint = contexts[0]['Endpoints']['docker']['Host']
            require(isinstance(endpoint, str) and re.fullmatch(r'npipe:////\./pipe/[A-Za-z0-9_.-]+', endpoint), '只允许本机 named pipe Docker。')
            rows = json.loads(self.command(['inspect', self.manifest['containerId']]))
            require(isinstance(rows, list) and len(rows) == 1, '演示容器响应无效。')
            row = rows[0]
            require(row['Id'] == self.manifest['containerId'] and row['Name'] == '/' + self.manifest['containerName'], '演示容器身份不匹配。')
            require(row['Config']['Labels'].get(PROJECT_LABEL) == self.manifest['projectRoot'] and
                    row['Config']['Labels'].get(RUN_LABEL) == self.manifest['runId'], '容器不是本轮演示所有。')
            require(re.fullmatch(r'mysql:8\.4(?:\.\d+)?(?:@sha256:[a-f0-9]{64})?', row['Config']['Image']), '演示容器镜像无效。')
            require(row['State']['Running'] is True, '演示数据库容器未运行。')
            host = row['HostConfig']
            require(not host.get('Binds') and not host.get('Privileged', False), '演示容器不允许宿主挂载或特权。')
            mounts = row.get('Mounts', [])
            require(all(mount.get('Type') == 'tmpfs' and mount.get('Destination') == '/var/lib/mysql' for mount in mounts), '演示容器不允许数据卷或其他挂载。')
            tmpfs = host.get('Tmpfs') or {}
            require(set(tmpfs) == {'/var/lib/mysql'} and isinstance(tmpfs['/var/lib/mysql'], str) and
                    sorted(tmpfs['/var/lib/mysql'].split(',')) == ['nodev', 'nosuid', 'rw', 'size=512m'], '演示容器临时挂载无效。')
            bindings = host.get('PortBindings', {}).get('3306/tcp')
            require(bindings == [{'HostIp': '127.0.0.1', 'HostPort': str(self.manifest['ports']['database'])}], '演示数据库端口映射无效。')
            for key, entries in (host.get('PortBindings') or {}).items():
                require(key == '3306/tcp' or not entries, '演示容器存在额外公开端口。')
            environment = dict(entry.split('=', 1) for entry in row['Config'].get('Env', []) if '=' in entry)
            require(environment.get('MYSQL_ROOT_PASSWORD') == self.manifest['database']['password'] and
                    environment.get('MYSQL_DATABASE') == 'attendance_demo', '演示数据库配置不匹配。')
        except DemoError:
            raise
        except (ValueError, TypeError, KeyError):
            raise DemoError('演示 Docker 元数据无效。') from None

    def sql(self, statement):
        self.validate()
        shell = ': "${MYSQL_ROOT_PASSWORD:?}"; export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql --protocol=tcp -h127.0.0.1 --connect-timeout=5 --default-character-set=utf8mb4 --batch --skip-column-names -uroot attendance_demo'
        return self.command(['exec', '-i', self.manifest['containerId'], 'sh', '-c', shell], "SET time_zone='+08:00';\n" + statement)


class DemoApi:
    def __init__(self, manifest, client):
        self.manifest, self.client = manifest, client
        self.base = 'http://127.0.0.1:' + str(manifest['ports']['backend'])

    def request(self, method, path, token=None, body=None):
        known = {'/login', '/dept', '/manager', '/employee', '/attendance/requests'}
        if path in known:
            safe_path = path
        elif re.fullmatch(r'/attendance/requests/\d+/decision', path):
            safe_path = '/attendance/requests/{id}/decision'
        else:
            raise DemoError('演示 API 路径无效。')
        require(method in {'GET', 'POST', 'PUT'}, '演示 API 方法无效。')
        context = method + ' ' + safe_path
        headers = {} if token is None else {'Authorization': 'Bearer ' + token}
        try:
            response = self.client.request(method, self.base + path, headers=headers, json=body)
            require(response.status_code == 200, '演示业务接口暂不可用（' + context + '，HTTP=' + str(response.status_code) + '）。')
            data = response.json()
            require(isinstance(data, dict), '演示业务接口响应格式无效（' + context + '）。')
            code = data.get('code')
            safe_code = code if isinstance(code, str) and re.fullmatch(r'[1-5]\d{2}', code) else 'INVALID'
            require(code == '200', '演示业务校验失败（' + context + '，code=' + safe_code + '）。')
            return data.get('data')
        except DemoError:
            raise
        except (httpx.HTTPError, ValueError, TypeError, AttributeError):
            raise DemoError('演示业务接口响应无效（' + context + '）。') from None

    def login(self, username, password, role):
        data = self.request('POST', '/login', body={'username': username, 'password': password})
        require(isinstance(data, dict) and isinstance(data.get('token'), str) and data['token'], '演示登录响应无效。')
        user = data.get('user', {})
        require(user.get('username') == username and user.get('role') == ('ADMIN' if role == 'admin' else role), '演示后端账号身份不匹配。')
        return data['token'], positive_id(user.get('id'))


def counts(database, statement, expected):
    result = database.sql(statement)
    require(result == '\t'.join(str(value) for value in expected), '演示数据库状态不符合新环境约束。')


def verify_data(database):
    counts(database, '''SELECT (SELECT COUNT(*) FROM sys_user WHERE deleted=0),(SELECT COUNT(*) FROM dept),
      (SELECT COUNT(*) FROM manager),(SELECT COUNT(*) FROM employee),(SELECT COUNT(*) FROM attendance_request),
      (SELECT COUNT(*) FROM attendance_request WHERE state='APPROVED'),(SELECT COUNT(*) FROM attendance_approval),
      (SELECT COUNT(*) FROM records),(SELECT COUNT(*) FROM records WHERE source='CORRECTION'),(SELECT COUNT(*) FROM face_template);''',
           [5, 2, 2, 2, 4, 4, 8, 3, 3, 0])


def exclusive_json(path, body):
    no_reparse(path)
    try:
        descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, 'w', encoding='utf-8', newline='\n') as stream:
            json.dump(body, stream, ensure_ascii=False, indent=2)
            stream.write('\n')
    except (OSError, ValueError):
        raise DemoError('演示结果文件已存在或无法安全写入。') from None


def seed(manifest_path, runner=None, transport=None, today=None):
    path, manifest = load_manifest(manifest_path)
    database = DemoDatabase(manifest, runner)
    database.validate()
    accounts_path, context_path = path.parent / 'accounts.json', path.parent / 'demo-context.json'
    no_reparse(accounts_path)
    no_reparse(context_path)
    with httpx.Client(timeout=20, follow_redirects=False, trust_env=False, transport=transport) as client:
        api = DemoApi(manifest, client)
        bootstrap = manifest['bootstrap']
        admin, admin_id = api.login(bootstrap['username'], bootstrap['password'], 'admin')
        if accounts_path.exists() or context_path.exists():
            require(accounts_path.exists() and context_path.exists(), '上次初始化未完整结束，请重新创建独立演示。')
            accounts, context = read_json(accounts_path), read_json(context_path)
            require(accounts.get('formatVersion') == 'attendance-demo-accounts-v1' and context.get('formatVersion') == 'attendance-demo-context-v1' and
                    accounts.get('runId') == manifest['runId'] and context.get('runId') == manifest['runId'] and
                    accounts.get('syntheticFixture') is True and context.get('syntheticFixture') is True, '演示完成记录无效。')
            entries = accounts.get('accounts', [])
            require(len(entries) == 5 and sorted(entry.get('role') for entry in entries) == ['admin', 'employee', 'employee', 'manager', 'manager'], '演示账号记录无效。')
            for entry in entries:
                require(entry.get('password') == bootstrap['password'], '演示账号凭据记录不匹配。')
                _, user_id = api.login(entry['username'], entry['password'], entry['role'])
                require(user_id == entry.get('userId'), '演示账号记录与后端不匹配。')
            verify_data(database)
            return context

        counts(database, '''SELECT (SELECT COUNT(*) FROM sys_user WHERE deleted=0),(SELECT COUNT(*) FROM dept),
          (SELECT COUNT(*) FROM manager),(SELECT COUNT(*) FROM employee),(SELECT COUNT(*) FROM records),
          (SELECT COUNT(*) FROM attendance_request),(SELECT COUNT(*) FROM attendance_day),(SELECT COUNT(*) FROM attendance_policy),
          (SELECT COUNT(*) FROM face_template),(SELECT COUNT(*) FROM attendance_export_job),(SELECT COUNT(*) FROM assistant_run);''',
               [1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0])
        password = bootstrap['password']
        accounts = [{'role': 'admin', 'username': bootstrap['username'], 'password': password, 'userId': admin_id, 'name': '演示管理员'}]
        department_rows, users = [], {'admin': admin_id}
        tokens = {}
        for key, name in (('a', '演示研发部'), ('b', '演示运营部')):
            api.request('POST', '/dept', admin, {'name': name})
            rows = api.request('GET', '/dept', admin)
            matches = [row for row in rows if row.get('name') == name]
            require(len(matches) == 1, '合成演示部门不唯一。')
            department_id = positive_id(matches[0].get('id'))
            department_rows.append({'key': key, 'id': department_id, 'name': name})
            for role in ('manager', 'employee'):
                # 账号Service派生username@example.invalid，邮箱最多30字，员工前缀须足够短。
                username = 'demo_' + ('emp' if role == 'employee' else role) + '_' + key
                person_name = ('演示主管' if role == 'manager' else '演示员工') + key.upper()
                api.request('POST', '/' + role, admin, {'username': username, 'name': person_name, 'initialPassword': password, 'deptId': department_id})
                token, user_id = api.login(username, password, role)
                users[role + '_' + key], tokens[role + '_' + key] = user_id, token
                accounts.append({'role': role, 'username': username, 'password': password, 'userId': user_id, 'name': person_name, 'departmentName': name})
                if role == 'manager':
                    api.request('PUT', '/dept', admin, {'id': department_id, 'name': name, 'userId': user_id})

        reference = today or datetime.now(SHANGHAI).date()
        business_date = reference - timedelta(days=1)
        while business_date.weekday() >= 5:
            business_date -= timedelta(days=1)
        anchor = business_date.replace(day=1)
        # 仅独立合成库构造可解释的过去时间轴；不通过SQL创建申请、审批或事件。
        user_ids = ','.join(str(positive_id(value)) for value in users.values())
        snapshots = ','.join("(%d,'%s',%d,true,(SELECT id FROM attendance_policy LIMIT 1),'08:00:00','18:00:00',0,NOW())" %
                             (users['employee_' + row['key']], business_date.isoformat(), row['id']) for row in department_rows)
        database.sql("UPDATE attendance_policy SET effective_from='%s',start_time='08:00:00',end_time='18:00:00',grace_minutes=0 WHERE created_by=0;\n"
                     "UPDATE sys_user SET create_time='%s 00:00:00' WHERE id IN (%s);\n"
                     "INSERT INTO attendance_day(user_id,business_date,dept_id,working_day,policy_id,start_time,end_time,grace_minutes,created_at) VALUES %s;" %
                     (anchor.isoformat(), anchor.isoformat(), user_ids, snapshots))

        request_rows = []
        def approve(key, body):
            # 日期化申请与报表/助手的编号规则不同：此接口不允许下划线。
            payload = {'requestKey': 'demo-' + manifest['runId'] + '-' + str(len(request_rows) + 1), 'reason': '独立合成演示申请，无真实人员考勤含义', **body}
            pending = api.request('POST', '/attendance/requests', tokens['employee_' + key], payload)
            request_id, version = positive_id(pending.get('id')), positive_id(pending.get('version'))
            approved = api.request('POST', '/attendance/requests/' + str(request_id) + '/decision', tokens['manager_' + key],
                                   {'version': version, 'decision': 'APPROVED', 'comment': '独立合成演示审批'})
            require(approved.get('id') == request_id and approved.get('state') == 'APPROVED', '合成申请未取得真实批准回执。')
            request_rows.append({'id': request_id, 'userId': users['employee_' + key], 'kind': body['kind'], 'state': 'APPROVED', **({'action': body['action']} if 'action' in body else {})})

        for action, clock in (('IN', '08:15:00'), ('OUT', '17:45:00')):
            approve('a', {'kind': 'CORRECTION', 'businessDate': business_date.isoformat(), 'action': action, 'correctionTime': business_date.isoformat() + ' ' + clock})
        approve('b', {'kind': 'LEAVE', 'startDate': business_date.isoformat(), 'endDate': business_date.isoformat(), 'startHalf': 'AM', 'endHalf': 'AM', 'leaveType': '事假'})
        approve('b', {'kind': 'CORRECTION', 'businessDate': business_date.isoformat(), 'action': 'OUT', 'correctionTime': business_date.isoformat() + ' 18:00:00'})
        verify_data(database)
        context = {'formatVersion': 'attendance-demo-context-v1', 'runId': manifest['runId'], 'syntheticFixture': True,
                   'month': business_date.strftime('%Y-%m'), 'businessDate': business_date.isoformat(), 'departments': department_rows,
                   'users': users, 'requests': request_rows, 'eventCount': 3, 'requestCount': 4, 'mode': 'MOCK', 'faceEnabled': False}
        exclusive_json(accounts_path, {'formatVersion': 'attendance-demo-accounts-v1', 'runId': manifest['runId'], 'syntheticFixture': True, 'accounts': accounts})
        exclusive_json(context_path, context)
        return context


def main(argv=None):
    parser = argparse.ArgumentParser(description='初始化本项目独立合成演示环境。')
    parser.add_argument('--manifest', required=True)
    args = parser.parse_args(argv)
    try:
        seed(args.manifest)
        return 0
    except DemoError as error:
        print(str(error), file=sys.stderr)
        return 1
    except Exception:
        print('独立演示初始化失败；未输出凭据，请重新创建本轮临时环境。', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
