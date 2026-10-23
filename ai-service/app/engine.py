"""无数据库、无文件写入的人脸计算模块；OpenCV 对象由调用方串行使用。"""
import base64
import binascii
import hashlib
import io
import json
from pathlib import Path
import cv2
import numpy as np
from PIL import Image, UnidentifiedImageError

ROOT = Path(__file__).resolve().parents[1]
MODEL_VERSION = 'sface-2021dec-yunet-2023mar-v1'

class FaceError(Exception):
    def __init__(self, code: str, message: str, status: int = 422):
        self.code, self.message, self.status = code, message, status

def decode_image(value: str) -> np.ndarray:
    if not value or len(value) > 7_000_000:
        raise FaceError('INVALID_IMAGE', '图片为空或超过5MB，请重新拍摄')
    if value.startswith('data:'):
        header, separator, value = value.partition(',')
        if not separator or header not in ('data:image/jpeg;base64', 'data:image/png;base64'):
            raise FaceError('INVALID_IMAGE', '仅支持JPEG或PNG图片')
    try:
        raw = base64.b64decode(value, validate=True)
        if len(raw) > 5 * 1024 * 1024:
            raise ValueError()
        with Image.open(io.BytesIO(raw)) as picture:
            width, height = picture.size
            if picture.format not in ('JPEG', 'PNG') or width < 160 or height < 160 or width * height > 4_000_000:
                raise ValueError()
            picture.verify()
        result = cv2.imdecode(np.frombuffer(raw, dtype=np.uint8), cv2.IMREAD_COLOR)
        if result is None:
            raise ValueError()
        return result
    except (ValueError, binascii.Error, UnidentifiedImageError, OSError, SyntaxError, Image.DecompressionBombError) as error:
        raise FaceError('INVALID_IMAGE', '图片损坏或尺寸不合适，请重新拍摄') from error

def validate_embeddings(value) -> np.ndarray:
    try:
        matrix = np.asarray(value, dtype=np.float32)
    except (ValueError, TypeError) as error:
        raise FaceError('INVALID_TEMPLATE', '模板无效，请重新录入') from error
    if matrix.ndim != 2 or matrix.shape[0] not in (1, 2, 3) or matrix.shape[1] != 128 or not np.isfinite(matrix).all():
        raise FaceError('INVALID_TEMPLATE', '模板无效，请重新录入')
    # 用 float64 计算模长，拒绝极端数值和溢出，不能把坏模板归一化成零向量。
    norms = np.linalg.norm(matrix.astype(np.float64), axis=1, keepdims=True)
    if not np.isfinite(norms).all() or np.any(norms < 1e-6):
        raise FaceError('INVALID_TEMPLATE', '模板无效，请重新录入')
    return (matrix / norms).astype(np.float32)

class FaceEngine:
    def __init__(self, models_dir: Path = ROOT / 'models'):
        manifest = json.loads((ROOT / 'models/manifest.json').read_text(encoding='utf-8'))
        buffers = []
        for model in manifest['models']:
            path = models_dir / model['name']
            if not path.is_file() or path.stat().st_size != model['size']:
                raise FaceError('MODELS_NOT_READY', '本地识别模型缺失或校验失败，请先准备模型', 503)
            raw = path.read_bytes()
            if hashlib.sha256(raw).hexdigest() != model['sha256']:
                raise FaceError('MODELS_NOT_READY', '本地识别模型缺失或校验失败，请先准备模型', 503)
            buffers.append(np.frombuffer(raw, dtype=np.uint8))
        cv2.setNumThreads(2)
        # Windows 下 OpenCV 文件入口无法可靠读取中文路径；Python 读取并校验后传内存。
        empty_config = np.empty(0, dtype=np.uint8)
        self.detector = cv2.FaceDetectorYN.create('onnx', buffers[0], empty_config, (320, 320), 0.9, 0.3, 5000)
        self.recognizer = cv2.FaceRecognizerSF.create('onnx', buffers[1], empty_config)

    def extract_one(self, value: str):
        image = decode_image(value)
        # 检测使用受限分辨率，特征对齐仍使用原图，避免大量像素拖垮推理。
        height, width = image.shape[:2]
        scale = min(1.0, 640 / max(width, height))
        detection_image = cv2.resize(image, None, fx=scale, fy=scale) if scale < 1 else image
        self.detector.setInputSize((detection_image.shape[1], detection_image.shape[0]))
        _, faces = self.detector.detect(detection_image)
        if faces is None or len(faces) == 0:
            raise FaceError('NO_FACE', '没有检测到人脸，请正对摄像头并改善光线')
        if len(faces) != 1:
            raise FaceError('MULTIPLE_FACES', '画面中有多张人脸，请保持本人单独入镜')
        face = faces[0].copy()
        face[:14] /= scale
        x, y, w, h = face[:4].astype(int)
        if min(w, h) < 80 or x < 0 or y < 0 or x + w > width or y + h > height:
            raise FaceError('FACE_TOO_SMALL', '请靠近摄像头，让完整面部出现在画面中')
        crop = image[y:y+h, x:x+w]
        gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
        brightness = float(gray.mean())
        if brightness < 40 or brightness > 220:
            raise FaceError('BAD_LIGHTING', '面部光线过暗或过亮，请调整光线后重拍')
        if float(cv2.Laplacian(gray, cv2.CV_64F).var()) < 35:
            raise FaceError('BLURRY_FACE', '面部不清晰，请保持稳定后重拍')
        aligned = self.recognizer.alignCrop(image, face)
        feature = np.asarray(self.recognizer.feature(aligned), dtype=np.float32)
        if feature.size != 128:
            raise FaceError('INVALID_FEATURE', '无法提取有效特征，请重新拍摄')
        feature = feature.reshape(128)
        norm = float(np.linalg.norm(feature.astype(np.float64)))
        if not np.isfinite(feature).all() or not np.isfinite(norm) or norm < 1e-6:
            raise FaceError('INVALID_FEATURE', '无法提取有效特征，请重新拍摄')
        return feature / norm

    def enroll(self, images: list[str]):
        vectors = np.stack([self.extract_one(image) for image in images])
        if len(vectors) > 1:
            scores = vectors @ vectors.T
            if float(scores[np.triu_indices(len(vectors), 1)].min()) < 0.45:
                raise FaceError('INCONSISTENT_SAMPLES', '采集照片差异过大，请由同一人重新拍摄三张正脸')
        return {'modelVersion': MODEL_VERSION, 'embeddings': vectors.tolist()}

    def compare(self, embeddings, image: str):
        templates = validate_embeddings(embeddings)
        query = self.extract_one(image)
        # 多张模板取平均，避免单个异常模板决定结果。
        score = float(np.clip((templates @ query).mean(), -1, 1))
        return {'modelVersion': MODEL_VERSION, 'score': score}
