import hashlib
import io

import pytest

from scripts.download_models import download, verified


@pytest.fixture
def model():
    return {'name': 'test.onnx', 'folder': 'test-model', 'size': 5, 'sha256': hashlib.sha256(b'model').hexdigest()}


def test_cache_requires_both_size_and_hash(tmp_path, model):
    path = tmp_path / model['name']
    assert not verified(path, model)
    path.write_bytes(b'wrong')
    assert not verified(path, model)
    path.write_bytes(b'model')
    assert verified(path, model)


def test_verified_download_atomically_replaces_target(monkeypatch, tmp_path, model):
    monkeypatch.setattr('scripts.download_models.urllib.request.urlopen', lambda url, timeout: io.BytesIO(b'model'))
    target = tmp_path / model['name']
    download(model, 'fixed-commit', target)
    assert target.read_bytes() == b'model'
    assert not target.with_suffix('.part').exists()


@pytest.mark.parametrize('payload', [b'short', b'too-long-model'])
def test_bad_download_preserves_existing_target_and_cleans_partial(monkeypatch, tmp_path, model, payload):
    monkeypatch.setattr('scripts.download_models.urllib.request.urlopen', lambda url, timeout: io.BytesIO(payload))
    target = tmp_path / model['name']
    target.write_bytes(b'previous-model')
    with pytest.raises(RuntimeError):
        download(model, 'fixed-commit', target)
    assert target.read_bytes() == b'previous-model'
    assert not target.with_suffix('.part').exists()
