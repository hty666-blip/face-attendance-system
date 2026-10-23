import base64
import io

import cv2
import numpy as np
import pytest
from PIL import Image

from app.engine import FaceEngine, FaceError, MODEL_VERSION, decode_image, validate_embeddings


def encoded_image(size=(160, 160), image_format='PNG'):
    output = io.BytesIO()
    Image.new('RGB', size, (120, 130, 140)).save(output, format=image_format)
    return base64.b64encode(output.getvalue()).decode('ascii')


@pytest.mark.parametrize('prefix', ['', 'data:image/png;base64,'])
def test_decodes_valid_png(prefix):
    assert decode_image(prefix + encoded_image()).shape == (160, 160, 3)


def test_decodes_jpeg():
    assert decode_image('data:image/jpeg;base64,' + encoded_image(image_format='JPEG')).shape == (160, 160, 3)


@pytest.mark.parametrize('image', ['', 'not base64!', base64.b64encode(b'not a picture').decode(), 'data:image/gif;base64,AAAA', 'data:image/png;base64', 'x' * 7_000_001], ids=['empty', 'invalid-base64', 'not-picture', 'wrong-data-uri', 'missing-comma', 'oversized'])
def test_invalid_image_reports_safe_error(image):
    with pytest.raises(FaceError) as failure:
        decode_image(image)
    assert failure.value.code == 'INVALID_IMAGE'
    if len(image) > 40:
        assert image[:40] not in failure.value.message


@pytest.mark.parametrize('size, image_format', [((159, 160), 'PNG'), ((2500, 2000), 'PNG'), ((160, 160), 'GIF')])
def test_rejects_unsupported_format_and_pixel_limits(size, image_format):
    with pytest.raises(FaceError) as failure:
        decode_image(encoded_image(size, image_format))
    assert failure.value.code == 'INVALID_IMAGE'


def test_corrupt_png_crc_is_a_quality_error():
    data = bytearray(base64.b64decode(encoded_image()))
    data[45] ^= 255
    with pytest.raises(FaceError) as failure:
        decode_image(base64.b64encode(data).decode('ascii'))
    assert failure.value.code == 'INVALID_IMAGE'


@pytest.mark.parametrize('template', [[], [[0.0] * 128], [[float('nan')] * 128], [[float('inf')] * 128], [[1.0] * 127], [[1.0] * 128] * 4, [['bad'] * 128], [[1.0], [2.0, 3.0]]])
def test_invalid_template(template):
    with pytest.raises(FaceError) as failure:
        validate_embeddings(template)
    assert failure.value.code == 'INVALID_TEMPLATE'


def test_embedding_normalization_is_finite_even_for_large_values():
    actual = validate_embeddings([[1e38] * 128])
    assert np.isfinite(actual).all()
    assert float(np.linalg.norm(actual[0])) == pytest.approx(1.0)


class Detector:
    def __init__(self, faces):
        self.faces = faces
        self.input_size = None
    def setInputSize(self, size):
        self.input_size = size
    def detect(self, image):
        return None, self.faces


class Recognizer:
    def __init__(self, vector=None):
        self.vector = vector if vector is not None else np.ones((1, 128), dtype=np.float32)
        self.face = None
    def alignCrop(self, image, face):
        self.face = face
        return image
    def feature(self, aligned):
        return self.vector


def fake_engine(monkeypatch, image, faces, feature=None):
    monkeypatch.setattr('app.engine.decode_image', lambda value: image)
    engine = FaceEngine.__new__(FaceEngine)
    engine.detector = Detector(faces)
    engine.recognizer = Recognizer(feature)
    return engine


FACE = np.asarray([30, 30, 120, 120] + [60, 60] * 5 + [0.99], dtype=np.float32)


@pytest.mark.parametrize('faces, code', [(None, 'NO_FACE'), ([], 'NO_FACE'), (np.stack([FACE, FACE]), 'MULTIPLE_FACES')])
def test_requires_exactly_one_face(monkeypatch, faces, code):
    engine = fake_engine(monkeypatch, np.zeros((200, 200, 3), dtype=np.uint8), faces)
    with pytest.raises(FaceError) as failure:
        engine.extract_one('test')
    assert failure.value.code == code


@pytest.mark.parametrize('bounds', [[20, 20, 70, 70], [-2, 20, 120, 120], [100, 100, 120, 120]])
def test_requires_complete_face_and_minimum_size(monkeypatch, bounds):
    face = FACE.copy()
    face[:4] = bounds
    engine = fake_engine(monkeypatch, np.zeros((200, 200, 3), dtype=np.uint8), np.stack([face]))
    with pytest.raises(FaceError) as failure:
        engine.extract_one('test')
    assert failure.value.code == 'FACE_TOO_SMALL'


@pytest.mark.parametrize('level, code', [(20, 'BAD_LIGHTING'), (240, 'BAD_LIGHTING'), (120, 'BLURRY_FACE')])
def test_rejects_bad_light_or_blur(monkeypatch, level, code):
    engine = fake_engine(monkeypatch, np.full((200, 200, 3), level, dtype=np.uint8), np.stack([FACE]))
    with pytest.raises(FaceError) as failure:
        engine.extract_one('test')
    assert failure.value.code == code


def noisy_image(size=200):
    return np.random.default_rng(7).integers(50, 200, (size, size, 3), dtype=np.uint8)


@pytest.mark.parametrize('feature', [np.zeros((1, 128)), np.full((1, 128), float('nan')), np.ones((1, 127))])
def test_invalid_model_output_is_rejected(monkeypatch, feature):
    engine = fake_engine(monkeypatch, noisy_image(), np.stack([FACE]), feature)
    with pytest.raises(FaceError) as failure:
        engine.extract_one('test')
    assert failure.value.code == 'INVALID_FEATURE'


def test_detection_downsamples_and_restores_face_coordinates(monkeypatch):
    engine = fake_engine(monkeypatch, noisy_image(1280), np.stack([FACE]))
    vector = engine.extract_one('test')
    assert engine.detector.input_size == (640, 640)
    assert engine.recognizer.face[:14] == pytest.approx(FACE[:14] * 2)
    assert float(np.linalg.norm(vector)) == pytest.approx(1)


def test_same_person_samples_and_average_cosine_match(monkeypatch):
    engine = FaceEngine.__new__(FaceEngine)
    vector = np.asarray([1.0] + [0.0] * 127, dtype=np.float32)
    monkeypatch.setattr(engine, 'extract_one', lambda value: vector)
    enrolled = engine.enroll(['a', 'b', 'c'])
    assert enrolled['modelVersion'] == MODEL_VERSION
    assert len(enrolled['embeddings']) == 3
    assert engine.compare(enrolled['embeddings'], 'a')['score'] == pytest.approx(1)
    second = [0.0, 1.0] + [0.0] * 126
    assert engine.compare([vector.tolist(), second], 'a')['score'] == pytest.approx(0.5)


def test_inconsistent_enrollment_is_rejected(monkeypatch):
    engine = FaceEngine.__new__(FaceEngine)
    vectors = iter([np.asarray([1.0] + [0.0] * 127), np.asarray([0.0, 1.0] + [0.0] * 126)])
    monkeypatch.setattr(engine, 'extract_one', lambda value: next(vectors))
    with pytest.raises(FaceError) as failure:
        engine.enroll(['a', 'b'])
    assert failure.value.code == 'INCONSISTENT_SAMPLES'


def test_model_missing_or_hash_mismatch_fails_before_opencv(tmp_path):
    with pytest.raises(FaceError) as failure:
        FaceEngine(tmp_path)
    assert failure.value.code == 'MODELS_NOT_READY'
    (tmp_path / 'face_detection_yunet_2023mar.onnx').write_bytes(b'\x00' * 232589)
    with pytest.raises(FaceError) as failure:
        FaceEngine(tmp_path)
    assert failure.value.code == 'MODELS_NOT_READY'
