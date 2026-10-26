import hmac
import logging
import os
import threading
import time
from contextlib import asynccontextmanager
from typing import Annotated
from fastapi import Depends, FastAPI, Header
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, FiniteFloat
from fastapi.exceptions import RequestValidationError
from starlette.types import ASGIApp, Receive, Scope, Send
import cv2
from .engine import FaceEngine, FaceError, MODEL_VERSION
from .assistant import AssistantConfig, MAX_ASSISTANT_BODY, install_assistant

log = logging.getLogger('face-service')
MAX_BODY_BYTES = 22_000_000
ImageValue = Annotated[str, Field(min_length=1, max_length=7_000_000)]
Embedding = Annotated[list[FiniteFloat], Field(min_length=128, max_length=128)]


class BoundedBodyMiddleware:
    """在 JSON 解析之前限制累计体积，同时支持没有 Content-Length 的传输。"""
    def __init__(self, app: ASGIApp, service_token: str, max_bytes: int = MAX_BODY_BYTES, assistant_token: str = ''):
        self.app, self.service_token, self.max_bytes = app, service_token, max_bytes
        self.assistant_token = assistant_token

    async def __call__(self, scope: Scope, receive: Receive, send: Send):
        if scope['type'] != 'http':
            await self.app(scope, receive, send)
            return
        headers = dict(scope['headers'])
        assistant = scope['path'].startswith('/assistant/')
        if assistant and not valid_token(headers.get(b'x-assistant-service-token', b''), self.assistant_token):
            await JSONResponse(status_code=401, content={'code': 'UNAUTHORIZED', 'msg': '内部服务鉴权失败'})(scope, receive, send)
            return
        if scope['method'] != 'POST':
            await self.app(scope, receive, send)
            return
        body_limit = MAX_ASSISTANT_BODY if assistant else self.max_bytes
        # 内部接口先鉴权，未授权调用不会触发图片读取、JSON 解析和推理。
        if scope['path'].startswith('/v1/') and not valid_token(
            headers.get(b'x-face-service-token', b''), self.service_token
        ):
            await JSONResponse(status_code=401, content={'code': 'UNAUTHORIZED', 'msg': '内部服务鉴权失败'})(scope, receive, send)
            return
        try:
            declared = int(headers.get(b'content-length', b'0'))
        except ValueError:
            declared = -1
        if declared < 0:
            await JSONResponse(status_code=400, content={'code': 'INVALID_REQUEST', 'msg': '请求格式不正确'})(scope, receive, send)
            return
        chunks, count = [], 0
        if declared <= body_limit:
            while True:
                message = await receive()
                if message['type'] == 'http.disconnect':
                    return
                count += len(message.get('body', b''))
                if count > body_limit:
                    break
                chunks.append(message)
                if not message.get('more_body', False):
                    break
        if declared > body_limit or count > body_limit:
            await JSONResponse(status_code=413, content={'code': 'BODY_TOO_LARGE', 'msg': '助手请求过大' if assistant else '图片请求过大'})(scope, receive, send)
            return
        # 已验证的有限数据再交给框架，避免框架把读取异常改写成 400。
        messages = iter(chunks)
        async def buffered_receive():
            return next(messages, {'type': 'http.request', 'body': b'', 'more_body': False})
        await self.app(scope, buffered_receive, send)


def valid_token(candidate: bytes, expected: str) -> bool:
    return len(expected) >= 32 and expected.isascii() and bool(expected.strip()) and hmac.compare_digest(candidate, expected.encode('ascii'))

class EnrollRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    images: list[ImageValue] = Field(min_length=1, max_length=3)

class CompareRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    image: ImageValue
    modelVersion: str = Field(max_length=100)
    embeddings: list[Embedding] = Field(min_length=1, max_length=3)

def create_app(engine=None, token=None, assistant_config=None, assistant_planner=None):
    service_token = token if token is not None else os.environ.get('FACE_INTERNAL_TOKEN', '')
    assistant_config = assistant_config if assistant_config is not None else AssistantConfig.from_env()
    gate = threading.Lock()

    @asynccontextmanager
    async def lifespan(app):
        app.state.engine = engine
        app.state.error = None
        if not valid_token(service_token.encode('utf-8'), service_token):
            app.state.error = 'SERVICE_NOT_CONFIGURED'
        elif app.state.engine is None:
            try:
                app.state.engine = FaceEngine()
            except Exception as error:
                app.state.error = 'MODELS_NOT_READY'
                log.error('face engine not ready: %s', type(error).__name__)
        yield

    app = FastAPI(title='Local face computation', lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)

    app.add_middleware(BoundedBodyMiddleware, service_token=service_token, assistant_token=assistant_config.token)
    install_assistant(app, assistant_config, valid_token, assistant_planner)

    @app.exception_handler(FaceError)
    async def face_error(request, error):
        return JSONResponse(status_code=error.status, content={'code': error.code, 'msg': error.message})

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request, error):
        return JSONResponse(status_code=422, content={'code': 'INVALID_REQUEST', 'msg': '请求格式不正确'})

    def authenticate(x_face_service_token: str = Header(default='')):
        if not valid_token(x_face_service_token.encode('utf-8'), service_token):
            raise FaceError('UNAUTHORIZED', '内部服务鉴权失败', 401)

    def ready_engine():
        if app.state.error or app.state.engine is None:
            raise FaceError(app.state.error or 'MODELS_NOT_READY', '本地人脸服务未就绪，请检查配置和模型', 503)
        return app.state.engine

    @app.get('/health')
    def health():
        available = app.state.engine is not None and not app.state.error
        return JSONResponse(status_code=200 if available else 503, content={'status': 'UP' if available else 'DOWN', 'modelVersion': MODEL_VERSION, 'reason': app.state.error})

    def execute(operation):
        runtime = ready_engine()
        if not gate.acquire(blocking=False):
            raise FaceError('BUSY', '识别服务忙，请稍后重试', 429)
        started = time.monotonic()
        try:
            result = operation(runtime)
            result['elapsedMs'] = round((time.monotonic() - started) * 1000)
            return result
        except cv2.error as error:
            raise FaceError('INFERENCE_FAILED', '本地推理失败，请重新拍摄或检查模型', 503) from error
        except FaceError:
            raise
        except Exception as error:
            # 不记录异常文本和输入，防止第三方解码错误携带照片或模板。
            log.error('face computation failed: %s', type(error).__name__)
            raise FaceError('INFERENCE_FAILED', '本地推理失败，请重新拍摄或检查模型', 503) from error
        finally:
            gate.release()

    @app.post('/v1/enroll', dependencies=[Depends(authenticate)])
    def enroll(body: EnrollRequest):
        return execute(lambda runtime: runtime.enroll(body.images))

    @app.post('/v1/compare', dependencies=[Depends(authenticate)])
    def compare(body: CompareRequest):
        if body.modelVersion != MODEL_VERSION:
            raise FaceError('MODEL_MISMATCH', '模板模型版本不兼容，请重新录入', 409)
        return execute(lambda runtime: runtime.compare(body.embeddings, body.image))

    return app

app = create_app()
