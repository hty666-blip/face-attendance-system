"""仅在准备阶段下载官方模型；运行服务不会自动下载。"""
import argparse
import hashlib
import json
from pathlib import Path
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]

def verified(path, model):
    if not path.is_file() or path.stat().st_size != model['size']:
        return False
    digest = hashlib.sha256()
    with path.open('rb') as source:
        while block := source.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest() == model['sha256']


def download(model, commit, target):
    url = f"https://media.githubusercontent.com/media/opencv/opencv_zoo/{commit}/models/{model['folder']}/{model['name']}"
    temporary = target.with_suffix('.part')
    try:
        with urllib.request.urlopen(url, timeout=60) as source, temporary.open('wb') as output:
            total = 0
            while block := source.read(1024 * 1024):
                total += len(block)
                if total > model['size']:
                    raise RuntimeError('模型大小不匹配')
                output.write(block)
        if not verified(temporary, model):
            raise RuntimeError(f"模型校验失败：{model['name']}")
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-only', action='store_true', help='仅校验已有文件，不联网下载')
    args = parser.parse_args()
    manifest = json.loads((ROOT / 'models/manifest.json').read_text(encoding='utf-8'))
    for model in manifest['models']:
        target = ROOT / 'models' / model['name']
        if verified(target, model):
            print(f"verified {model['name']}")
            continue
        if args.check_only:
            raise SystemExit(f"模型缺失或校验失败：{model['name']}")
        for attempt in range(3):
            try:
                download(model, manifest['source_commit'], target)
                break
            except (OSError, RuntimeError) as error:
                if attempt == 2:
                    raise SystemExit(f"模型下载失败：{model['name']}（{type(error).__name__}），请检查网络后重试") from None
                time.sleep(1 + attempt)
        print(f"downloaded and verified {model['name']}")

if __name__ == '__main__':
    main()
