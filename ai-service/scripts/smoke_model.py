"""显式传入一张获准使用的样本，仅验证真实模型推理链路，不测识别准确率。"""
import argparse
import base64
import json
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.engine import FaceEngine, FaceError, MODEL_VERSION  # noqa: E402
from app.main import create_app  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', type=Path, required=True)
    args = parser.parse_args()
    if not args.image.is_file() or args.image.stat().st_size > 5 * 1024 * 1024:
        parser.error('样本不存在或超过5MB')
    value = base64.b64encode(args.image.read_bytes()).decode('ascii')
    runtime = FaceEngine()
    enrollment = runtime.enroll([value])
    same_image = runtime.compare(enrollment['embeddings'], value)
    assert len(enrollment['embeddings']) == 1
    assert len(enrollment['embeddings'][0]) == 128
    assert same_image['score'] > 0.99
    # 空白图片必须拒绝，避免模型调用成功就被误判成有效人脸。
    _, blank = cv2.imencode('.png', np.full((480, 640, 3), 120, dtype=np.uint8))
    try:
        runtime.enroll([base64.b64encode(blank).decode('ascii')])
    except FaceError as error:
        assert error.code == 'NO_FACE'
    else:
        raise AssertionError('空白图片未被拒绝')
    # 使用进程内 HTTP 调用验证真实模型与接口契约，不读取任何真实凭证。
    smoke_token = 'smoke-process-only-not-a-deployment-secret'
    with TestClient(create_app(engine=runtime, token=smoke_token)) as client:
        assert client.get('/health').status_code == 200
        assert client.post('/v1/enroll', json={'images': [value]}).status_code == 401
        headers = {'X-Face-Service-Token': smoke_token}
        response = client.post('/v1/enroll', headers=headers, json={'images': [value]})
        assert response.status_code == 200
        compare = client.post('/v1/compare', headers=headers, json={'image': value, 'modelVersion': MODEL_VERSION, 'embeddings': response.json()['embeddings']})
        assert compare.status_code == 200 and compare.json()['score'] > 0.99
    print(json.dumps({'modelVersion': MODEL_VERSION, 'embeddingDimensions': 128, 'sameImageScore': round(same_image['score'], 6), 'blankImageRejected': True, 'authenticatedApiVerified': True, 'scope': 'inference smoke only; no accuracy or liveness validation'}))


if __name__ == '__main__':
    main()
