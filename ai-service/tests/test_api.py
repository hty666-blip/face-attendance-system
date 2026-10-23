import asyncio
import json
import threading
from concurrent.futures import ThreadPoolExecutor

import cv2
import pytest
from fastapi.testclient import TestClient

from app.engine import FaceError, MODEL_VERSION
from app.main import BoundedBodyMiddleware, create_app

TOKEN = 'unit-test-internal-service-token-32-bytes'
HEADERS = {'X-Face-Service-Token': TOKEN}
VECTOR = [1.0] + [0.0] * 127


class FakeEngine:
    def enroll(self, images):
        return {'modelVersion': MODEL_VERSION, 'embeddings': [VECTOR for _ in images]}

    def compare(self, embeddings, image):
        return {'modelVersion': MODEL_VERSION, 'score': 0.8}


@pytest.fixture
def client():
    with TestClient(create_app(engine=FakeEngine(), token=TOKEN)) as value:
        yield value


def test_health_and_authenticated_contract(client):
    assert client.get('/health').json() == {'status': 'UP', 'modelVersion': MODEL_VERSION, 'reason': None}
    result = client.post('/v1/enroll', headers=HEADERS, json={'images': ['test-image']}).json()
    assert result['modelVersion'] == MODEL_VERSION
    assert result['embeddings'] == [VECTOR]
    assert result['elapsedMs'] >= 0
    response = client.post('/v1/compare', headers=HEADERS, json={'image': 'test', 'modelVersion': MODEL_VERSION, 'embeddings': [VECTOR]})
    assert response.status_code == 200
    assert response.json()['score'] == 0.8


@pytest.mark.parametrize('candidate', [None, 'wrong-token', ''])
def test_unauthorized_body_is_never_echoed(client, candidate):
    headers = {'X-Face-Service-Token': candidate} if candidate is not None else {}
    response = client.post('/v1/enroll', headers=headers, content='secret-face-image-invalid-json')
    assert response.status_code == 401
    assert 'secret-face' not in response.text


@pytest.mark.parametrize('token', ['', 'short', ' ' * 32, '非' * 32])
def test_unconfigured_token_health_and_auth_fail_closed(token):
    with TestClient(create_app(engine=FakeEngine(), token=token)) as client:
        assert client.get('/health').status_code == 503
        assert client.get('/health').json()['reason'] == 'SERVICE_NOT_CONFIGURED'
        assert client.post('/v1/enroll', headers=HEADERS, json={'images': ['test']}).status_code == 401


@pytest.mark.parametrize('error', [FileNotFoundError('private-path'), cv2.error('private-image'), RuntimeError('private-key')])
def test_model_startup_failure_is_safe_and_retryable(monkeypatch, caplog, error):
    def fail():
        raise error
    monkeypatch.setattr('app.main.FaceEngine', fail)
    with TestClient(create_app(token=TOKEN)) as client:
        response = client.get('/health')
        assert response.status_code == 503
        assert response.json()['reason'] == 'MODELS_NOT_READY'
        response = client.post('/v1/enroll', headers=HEADERS, json={'images': ['test']})
        assert response.status_code == 503
        assert 'private' not in response.text
    assert 'private' not in caplog.text


@pytest.mark.parametrize('body', [
    {'images': []}, {'images': ['x'] * 4}, {'images': ['']},
    {'images': ['secret-face-image'], 'extra': 'private-template'},
    {'images': ['x' * 7_000_001]},
])
def test_invalid_enrollment_never_echoes_input(client, body):
    response = client.post('/v1/enroll', headers=HEADERS, json=body)
    assert response.status_code == 422
    assert response.json() == {'code': 'INVALID_REQUEST', 'msg': '请求格式不正确'}


@pytest.mark.parametrize('embeddings', [[], [[1.0] * 127], [[0.0] * 129], [VECTOR] * 4, [['private-template'] * 128]])
def test_invalid_template_shape_is_rejected_before_inference(client, embeddings):
    response = client.post('/v1/compare', headers=HEADERS, json={'image': 'private-image', 'modelVersion': MODEL_VERSION, 'embeddings': embeddings})
    assert response.status_code == 422
    assert 'private' not in response.text


def test_nan_template_and_model_mismatch_are_rejected(client):
    response = client.post('/v1/compare', headers={**HEADERS, 'Content-Type': 'application/json'}, content=json.dumps({'image': 'private-image', 'modelVersion': MODEL_VERSION, 'embeddings': [[float('nan')] * 128]}))
    assert response.status_code == 422
    assert 'private' not in response.text
    response = client.post('/v1/compare', headers=HEADERS, json={'image': 'test', 'modelVersion': 'different-model', 'embeddings': [VECTOR]})
    assert response.status_code == 409
    assert response.json()['code'] == 'MODEL_MISMATCH'


@pytest.mark.parametrize('error, code', [(FaceError('NO_FACE', '没有检测到人脸'), 'NO_FACE'), (cv2.error('private-image'), 'INFERENCE_FAILED'), (RuntimeError('private-template'), 'INFERENCE_FAILED')])
def test_inference_error_is_safe_and_gate_recovers(caplog, error, code):
    class FailingEngine(FakeEngine):
        def enroll(self, images):
            if images == ['fail']:
                raise error
            return super().enroll(images)
    with TestClient(create_app(engine=FailingEngine(), token=TOKEN)) as client:
        response = client.post('/v1/enroll', headers=HEADERS, json={'images': ['fail']})
        assert response.json()['code'] == code
        assert 'private' not in response.text
        assert client.post('/v1/enroll', headers=HEADERS, json={'images': ['good']}).status_code == 200
    assert 'private' not in caplog.text


def test_busy_request_does_not_queue_another_inference():
    entered, release = threading.Event(), threading.Event()
    class BlockingEngine(FakeEngine):
        def enroll(self, images):
            entered.set()
            assert release.wait(5)
            return super().enroll(images)
    with TestClient(create_app(engine=BlockingEngine(), token=TOKEN)) as client:
        with ThreadPoolExecutor(max_workers=1) as pool:
            pending = pool.submit(client.post, '/v1/enroll', headers=HEADERS, json={'images': ['test']})
            try:
                assert entered.wait(5)
                busy = client.post('/v1/enroll', headers=HEADERS, json={'images': ['second']})
                assert busy.status_code == 429
                assert busy.json()['code'] == 'BUSY'
            finally:
                release.set()
            assert pending.result(timeout=5).status_code == 200


@pytest.mark.parametrize('declared, chunks', [(b'21', [b'{}']), (None, [b'1234567890', b'12345678901']), (b'2', [b'1234567890', b'12345678901'])])
def test_body_limit_covers_declared_chunked_and_false_length(declared, chunks):
    called = False
    async def downstream(scope, receive, send):
        nonlocal called
        called = True
    headers = [(b'x-face-service-token', TOKEN.encode())]
    if declared is not None:
        headers.append((b'content-length', declared))
    scope = {'type': 'http', 'method': 'POST', 'path': '/v1/enroll', 'headers': headers}
    messages = iter([{'type': 'http.request', 'body': chunk, 'more_body': i < len(chunks) - 1} for i, chunk in enumerate(chunks)])
    sent = []
    async def receive():
        return next(messages)
    async def send(message):
        sent.append(message)
    asyncio.run(BoundedBodyMiddleware(downstream, TOKEN, max_bytes=20)(scope, receive, send))
    assert sent[0]['status'] == 413
    assert not called


def test_unauthenticated_asgi_call_does_not_read_body():
    async def forbidden(*args):
        raise AssertionError('must not read or process unauthenticated body')
    sent = []
    async def send(message):
        sent.append(message)
    scope = {'type': 'http', 'method': 'POST', 'path': '/v1/enroll', 'headers': []}
    asyncio.run(BoundedBodyMiddleware(forbidden, TOKEN)(scope, forbidden, send))
    assert sent[0]['status'] == 401
