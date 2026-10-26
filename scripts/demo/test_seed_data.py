"""合成 Mock 边界测试；不启动 Docker，不连接任何数据库或业务服务。"""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import re
import subprocess
import tempfile
from datetime import date, datetime
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import httpx

SPEC = importlib.util.spec_from_file_location('demo_seed_data', Path(__file__).with_name('seed-data.py'))
seed_data = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(seed_data)


class SyntheticApi:
    def __init__(self, manifest):
        self.manifest, self.calls, self.departments, self.requests = manifest, [], [], []
        self.users = {manifest['bootstrap']['username']: {'id': 1, 'username': manifest['bootstrap']['username'], 'role': 'ADMIN'}}
        self.tokens = {}

    def response(self, data=None, code='200'):
        return httpx.Response(200, json={'code': code, 'msg': 'synthetic test response', 'data': data})

    @staticmethod
    def valid_application(body):
        # 按真实AttendanceRequestService.CreateInput/normalize契约做严格模拟，不能宽松接收任意JSON。
        if not isinstance(body, dict) or not isinstance(body.get('requestKey'), str) or not re.fullmatch(r'[A-Za-z0-9-]{32,64}', body['requestKey']):
            return False
        if not isinstance(body.get('reason'), str) or not 1 <= len(body['reason'].strip()) <= 1000:
            return False
        base = {'requestKey', 'kind', 'reason'}
        try:
            if body.get('kind') == 'CORRECTION':
                if set(body) != base | {'businessDate', 'action', 'correctionTime'} or body['action'] not in ('IN', 'OUT'):
                    return False
                if not re.fullmatch(r'\d{4}-\d{2}-\d{2}', body['businessDate']) or not re.fullmatch(r'\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}', body['correctionTime']):
                    return False
                business_date = date.fromisoformat(body['businessDate'])
                occurred = datetime.strptime(body['correctionTime'], '%Y-%m-%d %H:%M:%S')
                return occurred.date() == business_date
            if body.get('kind') == 'LEAVE':
                if set(body) != base | {'startDate', 'startHalf', 'endDate', 'endHalf', 'leaveType'}:
                    return False
                return (re.fullmatch(r'\d{4}-\d{2}-\d{2}', body['startDate']) is not None and
                        re.fullmatch(r'\d{4}-\d{2}-\d{2}', body['endDate']) is not None and
                        date.fromisoformat(body['startDate']) <= date.fromisoformat(body['endDate']) and
                        body['startHalf'] in ('AM', 'PM') and body['endHalf'] in ('AM', 'PM') and
                        body['leaveType'] in ('事假', '病假', '婚假', '丧假'))
        except (ValueError, TypeError, KeyError):
            return False
        return False

    def __call__(self, request):
        assert request.url.host == '127.0.0.1' and request.url.port == self.manifest['ports']['backend']
        body = json.loads(request.content) if request.content else None
        self.calls.append((request.method, request.url.path, body, request.headers.get('Authorization')))
        if request.url.path == '/login':
            if body['username'] not in self.users or body['password'] != self.manifest['bootstrap']['password']:
                return self.response(code='401')
            token = 'private-jwt-' + body['username']
            self.tokens[token] = self.users[body['username']]
            return self.response({'token': token, 'user': self.users[body['username']]})
        actor = self.tokens[request.headers['Authorization'].removeprefix('Bearer ')]
        if request.url.path == '/dept':
            if request.method == 'GET':
                return self.response(self.departments)
            assert actor['role'] == 'ADMIN'
            if request.method == 'POST':
                self.departments.append({'id': len(self.departments) + 1, **body})
            else:
                self.departments[body['id'] - 1].update(body)
            return self.response()
        if request.url.path in ('/manager', '/employee'):
            assert actor['role'] == 'ADMIN'
            username = body['username']
            assert username not in self.users and body['initialPassword'] == self.manifest['bootstrap']['password']
            if len(username + '@example.invalid') > 30:
                return self.response(code='500')
            self.users[username] = {'id': len(self.users) + 1, 'username': username, 'name': body['name'], 'role': request.url.path[1:], 'deptId': body['deptId']}
            return self.response()
        if request.url.path == '/attendance/requests':
            assert actor['role'] == 'employee'
            if not self.valid_application(body):
                return self.response(code='422')
            row = {'id': len(self.requests) + 1, 'version': 1, 'state': 'PENDING', 'userId': actor['id'], **body}
            self.requests.append(row)
            return self.response(row)
        match = re.fullmatch(r'/attendance/requests/(\d+)/decision', request.url.path)
        assert match and actor['role'] == 'manager'
        row = self.requests[int(match[1]) - 1]
        applicant = next(user for user in self.users.values() if user['id'] == row['userId'])
        assert self.departments[applicant['deptId'] - 1]['userId'] == actor['id']
        assert set(body) == {'version', 'decision', 'comment'} and type(body['version']) is int
        assert body['version'] == 1 and body['decision'] == 'APPROVED'
        row.update(state='APPROVED', version=2)
        return self.response(row)


class SyntheticDocker:
    def __init__(self, manifest, api):
        self.manifest, self.api, self.calls, self.sql_calls = manifest, api, [], []
        self.endpoint = 'npipe:////./pipe/dockerDesktopLinuxEngine'
        self.container = {
            'Id': manifest['containerId'], 'Name': '/' + manifest['containerName'], 'State': {'Running': True},
            'Config': {'Image': 'mysql:8.4', 'Labels': {seed_data.PROJECT_LABEL: manifest['projectRoot'], seed_data.RUN_LABEL: manifest['runId']},
                       'Env': ['MYSQL_ROOT_PASSWORD=' + manifest['database']['password'], 'MYSQL_DATABASE=attendance_demo']},
            'HostConfig': {'Binds': None, 'Privileged': False, 'Tmpfs': {'/var/lib/mysql': 'rw,nosuid,nodev,size=512m'},
                           'PortBindings': {'3306/tcp': [{'HostIp': '127.0.0.1', 'HostPort': str(manifest['ports']['database'])}]}},
            'Mounts': [],
        }

    def __call__(self, arguments, **options):
        self.calls.append((arguments, options))
        assert arguments[:3] == ['docker', '--context', self.manifest['dockerContext']]
        assert not any(key in options['env'] for key in ('DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH'))
        assert self.manifest['database']['password'] not in ' '.join(arguments)
        if arguments[3] == 'context':
            stdout = json.dumps([{'Endpoints': {'docker': {'Host': self.endpoint}}}])
        elif arguments[3] == 'inspect':
            assert arguments[4] == self.manifest['containerId']
            stdout = json.dumps([self.container])
        else:
            assert arguments[3:6] == ['exec', '-i', self.manifest['containerId']]
            assert arguments[-1].endswith('-uroot attendance_demo')
            statement = options['input']
            assert statement.startswith("SET time_zone='+08:00';")
            self.sql_calls.append(statement)
            if 'FROM assistant_run' in statement:
                stdout = '1\t0\t0\t0\t0\t0\t0\t1\t0\t0\t0'
            elif 'FROM attendance_approval' in statement:
                approved = [row for row in self.api.requests if row['state'] == 'APPROVED']
                events = [row for row in approved if row['kind'] == 'CORRECTION']
                stdout = '\t'.join(map(str, [len(self.api.users), len(self.api.departments), 2, 2, len(self.api.requests), len(approved), len(approved) * 2, len(events), len(events), 0]))
            else:
                assert 'UPDATE attendance_policy' in statement and 'INSERT INTO attendance_day' in statement
                stdout = ''
        return subprocess.CompletedProcess(arguments, 0, stdout=stdout, stderr='')


class SeedDataTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.root_patch = patch.object(seed_data, 'PROJECT_ROOT', self.root)
        self.root_patch.start()
        self.run_id = 'a' * 32
        self.run_dir = self.root / '.local' / 'demo' / self.run_id
        self.run_dir.mkdir(parents=True)
        self.manifest_path = self.run_dir / 'manifest.json'
        self.manifest = {
            'formatVersion': seed_data.FORMAT, 'runId': self.run_id, 'projectRoot': str(self.root),
            'dockerContext': 'desktop-linux', 'containerId': 'b' * 64, 'containerName': 'attendance-demo-' + self.run_id,
            'ports': {'database': 13307, 'backend': 19091, 'assistant': 8011, 'frontend': 7001},
            'database': {'name': 'attendance_demo', 'username': 'root', 'password': 'test-only-random-password-0123456789'},
            'bootstrap': {'username': 'demo_admin', 'password': 'test-only-admin-password-0123456789'}, 'ready': False, 'ended': False,
        }
        self.save_manifest()
        self.api = SyntheticApi(self.manifest)
        self.docker = SyntheticDocker(self.manifest, self.api)

    def tearDown(self):
        self.root_patch.stop()
        self.temporary.cleanup()

    def save_manifest(self, bom=False):
        self.manifest_path.write_text(json.dumps(self.manifest), encoding='utf-8-sig' if bom else 'utf-8')

    def execute(self):
        return seed_data.seed(str(self.manifest_path), runner=self.docker, transport=httpx.MockTransport(self.api), today=date(2026, 10, 26))

    def assert_no_sql(self):
        self.assertEqual([], self.docker.sql_calls)

    def test_bom_manifest_and_full_synthetic_api_approval_flow(self):
        self.save_manifest(bom=True)
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            context = self.execute()
        self.assertEqual('', stdout.getvalue() + stderr.getvalue())
        self.assertEqual('2026-10-23', context['businessDate'])
        self.assertEqual('2026-10', context['month'])
        self.assertTrue(context['syntheticFixture'])
        self.assertEqual(3, context['eventCount'])
        self.assertEqual(4, len(self.api.requests))
        self.assertEqual(['CORRECTION', 'CORRECTION', 'LEAVE', 'CORRECTION'], [row['kind'] for row in self.api.requests])
        self.assertTrue(all(row['state'] == 'APPROVED' for row in self.api.requests))
        self.assertTrue(all(re.fullmatch(r'[A-Za-z0-9-]{32,64}', row['requestKey']) for row in self.api.requests))
        corrections = [row for row in self.api.requests if row['kind'] == 'CORRECTION']
        self.assertEqual(['2026-10-23 08:15:00', '2026-10-23 17:45:00', '2026-10-23 18:00:00'], [row['correctionTime'] for row in corrections])
        self.assertEqual('AM', self.api.requests[2]['startHalf'])
        self.assertEqual(4, sum(path.endswith('/decision') for _, path, _, _ in self.api.calls))
        self.assertFalse(any(re.search(r'INSERT\s+INTO\s+(records|attendance_request|attendance_approval)', statement, re.I) for statement in self.docker.sql_calls))
        stored = (self.run_dir / 'accounts.json').read_text(encoding='utf-8')
        self.assertNotIn('private-jwt', stored)
        self.assertNotIn('private-jwt', (self.run_dir / 'demo-context.json').read_text(encoding='utf-8'))
        self.assertEqual(5, len(json.loads(stored)['accounts']))

    def test_repeat_only_verifies_and_preserves_files_and_business_facts(self):
        first = self.execute()
        files = {name: (self.run_dir / name).read_bytes() for name in ('accounts.json', 'demo-context.json')}
        call_count = len(self.api.calls)
        sql_count = len(self.docker.sql_calls)
        second = self.execute()
        self.assertEqual(first, second)
        for name, content in files.items():
            self.assertEqual(content, (self.run_dir / name).read_bytes())
        self.assertEqual(4, len(self.api.requests))
        self.assertTrue(all(path == '/login' for _, path, _, _ in self.api.calls[call_count:]))
        self.assertEqual(1, len(self.docker.sql_calls[sql_count:]))

    def test_manifest_outside_project_or_wrong_root_is_rejected_before_docker(self):
        outside = self.root / 'manifest.json'
        outside.write_text('{}', encoding='utf-8')
        with self.assertRaises(seed_data.DemoError):
            seed_data.seed(str(outside), runner=self.docker)
        self.manifest['projectRoot'] = str(self.root / 'different')
        self.save_manifest()
        with self.assertRaises(seed_data.DemoError):
            self.execute()
        self.assertEqual([], self.docker.calls)

    def test_reparse_directory_is_rejected_before_docker(self):
        original = Path.lstat
        def lstat(path):
            result = original(path)
            if path == self.run_dir:
                return SimpleNamespace(st_mode=result.st_mode, st_file_attributes=0x400)
            return result
        with patch.object(Path, 'lstat', lstat), self.assertRaises(seed_data.DemoError):
            self.execute()
        self.assertEqual([], self.docker.calls)

    def test_remote_docker_context_is_rejected_before_sql_or_http(self):
        self.docker.endpoint = 'tcp://example.invalid:2376'
        with self.assertRaises(seed_data.DemoError):
            self.execute()
        self.assert_no_sql()
        self.assertEqual([], self.api.calls)

    def test_wrong_labels_and_container_id_are_rejected_before_sql(self):
        for field in ('label', 'id', 'name'):
            with self.subTest(field=field):
                self.docker = SyntheticDocker(self.manifest, self.api)
                if field == 'label':
                    self.docker.container['Config']['Labels'][seed_data.RUN_LABEL] = 'c' * 32
                elif field == 'id':
                    self.docker.container['Id'] = 'c' * 64
                else:
                    self.docker.container['Name'] = '/unrelated-service'
                with self.assertRaises(seed_data.DemoError):
                    self.execute()
                self.assert_no_sql()

    def test_fixed_database_and_unique_loopback_ports_are_required(self):
        for change in ('database', 'duplicate_port', 'boolean_port'):
            with self.subTest(change=change):
                original = json.loads(json.dumps(self.manifest))
                if change == 'database':
                    self.manifest['database']['name'] = 'original_database'
                elif change == 'duplicate_port':
                    self.manifest['ports']['backend'] = self.manifest['ports']['database']
                else:
                    self.manifest['ports']['backend'] = True
                self.save_manifest()
                with self.assertRaises(seed_data.DemoError):
                    self.execute()
                self.assertEqual([], self.docker.calls)
                self.manifest = original

    def test_container_mounts_are_tmpfs_only_and_destination_and_options_are_fixed(self):
        self.docker.container['Mounts'] = [{'Type': 'tmpfs', 'Destination': '/var/lib/mysql'}]
        seed_data.DemoDatabase(self.manifest, self.docker).validate()
        variants = [
            {'mounts': [{'Type': 'volume', 'Destination': '/var/lib/mysql'}]},
            {'mounts': [{'Type': 'bind', 'Destination': '/var/lib/mysql'}]},
            {'mounts': [{'Type': 'tmpfs', 'Destination': '/other'}]},
            {'tmpfs': {'/var/lib/mysql': 'rw,nosuid,nodev,size=512m', '/other': 'rw'}},
            {'tmpfs': {'/var/lib/mysql': 'rw,exec,size=512m'}},
        ]
        for variant in variants:
            with self.subTest(variant=variant):
                self.docker = SyntheticDocker(self.manifest, self.api)
                self.docker.container['Mounts'] = variant.get('mounts', [])
                if 'tmpfs' in variant:
                    self.docker.container['HostConfig']['Tmpfs'] = variant['tmpfs']
                with self.assertRaises(seed_data.DemoError):
                    self.execute()
                self.assert_no_sql()

    def test_container_public_port_or_other_image_cannot_be_used(self):
        for change in ('port', 'image', 'secret'):
            self.docker = SyntheticDocker(self.manifest, self.api)
            if change == 'port':
                self.docker.container['HostConfig']['PortBindings']['3306/tcp'][0]['HostIp'] = '0.0.0.0'
            elif change == 'image':
                self.docker.container['Config']['Image'] = 'unrelated:latest'
            else:
                self.docker.container['Config']['Env'][0] = 'MYSQL_ROOT_PASSWORD=unrelated'
            with self.assertRaises(seed_data.DemoError):
                self.execute()
            self.assert_no_sql()

    def test_every_sql_revalidates_ownership_instead_of_trusting_initial_check(self):
        seed_data.DemoDatabase(self.manifest, self.docker).validate()
        self.docker.container['Config']['Labels'][seed_data.PROJECT_LABEL] = 'other-project'
        database = seed_data.DemoDatabase(self.manifest, self.docker)
        with self.assertRaises(seed_data.DemoError):
            database.sql('SELECT 1;')
        self.assert_no_sql()

    def test_partial_completion_is_not_overwritten_or_reseeded(self):
        path = self.run_dir / 'accounts.json'
        path.write_text('{"partial":true}', encoding='utf-8')
        with self.assertRaises(seed_data.DemoError):
            self.execute()
        self.assertEqual('{"partial":true}', path.read_text(encoding='utf-8'))
        self.assert_no_sql()

    def test_cli_errors_are_redacted_and_do_not_print_private_exception(self):
        output, errors = io.StringIO(), io.StringIO()
        private = self.manifest['bootstrap']['password']
        with patch.object(seed_data, 'seed', side_effect=RuntimeError(private)), contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            self.assertEqual(1, seed_data.main(['--manifest', str(self.manifest_path)]))
        self.assertEqual('', output.getvalue())
        self.assertNotIn(private, errors.getvalue())

    def test_derived_account_email_fits_real_backend_limit(self):
        self.execute()
        self.assertIn('demo_emp_a', self.api.users)
        self.assertIn('demo_emp_b', self.api.users)
        for username, user in self.api.users.items():
            if user['role'] != 'ADMIN':
                self.assertLessEqual(len(username + '@example.invalid'), 30)

    def test_api_errors_show_only_fixed_method_path_and_numeric_code(self):
        private = self.manifest['bootstrap']['password']
        for code in ('500', private):
            transport = httpx.MockTransport(lambda request: httpx.Response(200, json={'code': code, 'msg': private, 'data': {'password': private}}))
            with httpx.Client(transport=transport, trust_env=False) as client:
                api = seed_data.DemoApi(self.manifest, client)
                with self.assertRaises(seed_data.DemoError) as error:
                    api.request('POST', '/attendance/requests/123/decision', token=private, body={'password': private})
            text = str(error.exception)
            self.assertIn('POST /attendance/requests/{id}/decision', text)
            self.assertIn('code=' + ('500' if code == '500' else 'INVALID'), text)
            self.assertNotIn('123', text)
            self.assertNotIn(private, text)

    def test_request_contract_rejects_underscores_alias_fields_and_iso_t_time(self):
        valid = {'requestKey': 'demo-' + self.run_id + '-1', 'kind': 'CORRECTION', 'reason': '合成说明',
                 'businessDate': '2026-10-23', 'action': 'IN', 'correctionTime': '2026-10-23 08:15:00'}
        self.assertTrue(SyntheticApi.valid_application(valid))
        invalid = [
            {**valid, 'requestKey': 'demo_' + self.run_id + '_1'},
            {**valid, 'correctionTime': '2026-10-23T08:15:00'},
            {**valid, 'correctionTime': '2026-10-24 08:15:00'},
            {**valid, 'action': 'CHECK_IN'},
            {**valid, 'date': valid['businessDate']},
        ]
        alias = dict(valid)
        alias['date'] = alias.pop('businessDate')
        invalid.append(alias)
        for payload in invalid:
            with self.subTest(fields=sorted(payload)):
                self.assertFalse(SyntheticApi.valid_application(payload))


if __name__ == '__main__':
    unittest.main()
