# 本地人脸计算服务

该进程只负责图片检查、YuNet 单脸检测和 SFace 特征计算。Java 负责登录、人员权限、模板加密、打卡会话和考勤写入。Python 不连接业务数据库，不保存照片，不决定考勤是否成功，也不接受浏览器直接调用。

## 环境与启动

建议使用独立的 Python 3.12 虚拟环境。依赖锁定在 `requirements.txt`，安装、准备模型与运行均在本目录执行：

```powershell
python -m venv .venv
& ./.venv/Scripts/python.exe -m pip install -r requirements.txt
& ./.venv/Scripts/python.exe scripts/download_models.py
& ./.venv/Scripts/python.exe scripts/download_models.py --check-only
```

模型下载会重试并校验文件长度和 SHA-256；服务启动只检查现有文件，不联网下载。`models/manifest.json` 固定了 OpenCV Zoo 提交和两个模型的校验值，模型文件及虚拟环境不提交 Git。

运行前，给本进程与 Java 后端配置相同的 `FACE_INTERNAL_TOKEN`（随机 ASCII 字符串，至少 32 个字符）。不要把它写进源码或公开命令示例。项目启动脚本会使用本地私密配置；手动运行的入口为：

```powershell
& ./.venv/Scripts/python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8010 --workers 1 --limit-concurrency 8 --timeout-keep-alive 5
```

`FACE_INTERNAL_TOKEN` 与 Java 使用的 `FACE_TEMPLATE_KEY` 是两种独立密钥。前者认证内部调用，后者加密持久化模板，Python 不需要也不读取后者。当前部署只监听本机；跨机器部署时，需要另行配置私有网络、TLS 与网关请求超时。

## 内部接口

除 `/health` 外，所有接口必须提供 `X-Face-Service-Token`。请求、响应不记录图片或特征；异常只返回固定错误码和中文提示。

| 接口 | 请求 | 响应与约束 |
|---|---|---|
| `GET /health` | 无 | `status`、`modelVersion`、`reason`；模型缺失、损坏或 token 未配置时返回 503 |
| `POST /v1/enroll` | `images`：1～3 张图片 | 返回版本与 1～3 个 128 维单位特征；Java 新录入要求三张，旧照片迁移允许一张 |
| `POST /v1/compare` | `image`、`modelVersion`、`embeddings` | 返回多个模板余弦相似度的平均值；是否通过由 Java 阈值决定 |

支持 JPEG、PNG 的 Base64 或对应 Data URI。单张图片限制为 5 MiB、至少 160×160、最多 400 万像素；JSON 请求累计限制 2200 万字节，包括无 `Content-Length` 的分块传输。未授权请求在读取正文前拒绝，推理一次只执行一个任务，其他请求返回 `BUSY`（429）。

模型版本不符返回 409，格式、多人入镜、无人脸、光线或清晰度不合适返回 422，模型或推理不可用返回 503。Java 应保留这些可重试与不可重试的区别。

## 质量规则与边界

检测输入最长边缩到 640 像素，特征对齐仍使用原图。画面必须恰好一张完整面部，面部区域至少 80×80，灰度均值在 40～220，Laplacian 方差至少 35。多张录入样本任意两张的余弦相似度必须达到 0.45。这些是首版初始参数，需要结合实际摄像头、光线和获准采集的人员样本校准；不能据此宣称识别准确率。

当前不支持活体检测，照片、视频重放等展示攻击仍有风险。实际验收需要自愿参与者的重复采集、本人/非本人比对、设备与光线覆盖；本仓库没有提供或公开真实员工人脸样本。

## 验证

```powershell
& ./.venv/Scripts/python.exe -m pytest -q
# 仅在本机显式提供一张获准使用、能检测到单脸的图片：
& ./.venv/Scripts/python.exe scripts/smoke_model.py --image 'D:/private-test/sample.jpg'
```

单元测试不下载模型，也不使用真人样本，覆盖鉴权、请求大小、格式与安全错误、模型失效、并发忙碌、单脸/光线/清晰度、模板维度与有限值。smoke 使用真实模型校验特征提取、同图比对和空白图片拒绝，仅证明推理链路可运行。它不能替代不同图片的本人/非本人准确率、活体能力或真实摄像头验收。

2026-10-23 本地验证：72 项单元测试通过。真实模型在项目的 Windows 中文路径下完成 128 维特征提取、同图余弦相似度 1.0、空白图片拒绝，以及携带 token 的录入/比较接口调用。兼容现有 `httpx` 的测试客户端会报告一条弃用提示，不影响当前测试。使用的公开样本来自 [OpenCV 4.13.0 测试数据](https://github.com/opencv/opencv/blob/4.13.0/samples/data/lena.jpg)，图片和具体结果只保留在本机 `.local/`，没有加入版本库。模型通过 Python 读取并经内存传入 OpenCV，避免 Windows 中文目录的模型文件读取问题。

## 模型来源与许可

模型固定来源为 [OpenCV Zoo 提交 48b10fa](https://github.com/opencv/opencv_zoo/tree/48b10fa505aeb51fbd127fc60a5c660040e823b7)。YuNet 使用 [MIT 许可](models/YUNET-LICENSE.txt)，SFace 使用 [Apache 2.0 许可](models/SFACE-LICENSE.txt)。许可原文从同一提交的对应模型目录保留，重新分发模型时一并保留。模型调用方式参考 [OpenCV 官方人脸教程](https://docs.opencv.org/4.x/d0/dd4/tutorial_dnn_face.html)。
