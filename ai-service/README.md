# 本地人脸计算与AI计划服务

该进程负责人脸图片检查、YuNet 单脸检测、SFace 特征计算，以及独立的 LangChain 考勤工具计划。Java 负责登录、人员权限、业务事务和工具执行。Python 不连接业务数据库，不保存照片，不决定考勤是否成功，也不接受浏览器直接调用。

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

## AI考勤计划接口

聊天与人脸使用独立认证与并发预算。`GET /assistant/health`、`POST /assistant/v1/plan` 均要求 `X-Assistant-Service-Token`，对应环境变量 `ASSISTANT_INTERNAL_TOKEN`，至少32个ASCII字符。人脸模型缺失不会阻止已配置的聊天，模型故障也不影响人脸接口。

默认 `ASSISTANT_ENABLED=false`，不会调用模型。启用后须明确选择 `ASSISTANT_PROVIDER=mock` 或 `openai-compatible`。Mock为中文规则演示，响应始终标记 `MOCK`；真实模式还需要管理员明确提供 `ASSISTANT_API_KEY`、`ASSISTANT_MODEL`。可选 `ASSISTANT_BASE_URL` 仅来自进程配置，允许HTTPS或本机HTTP，不接受请求动态指定的URL。真实模式配置缺失或调用失败不会退回Mock，不会自动开通付费服务。

`ASSISTANT_TIMEOUT_SECONDS` 默认15秒，环境配置限0.05～30秒；`ASSISTANT_MAX_CONCURRENCY` 默认2，限1～4，超额立即429。JSON正文在读取和解析前鉴权，累计上限200KiB（包括分块传输和伪造长度）。固定错误码区分未配置、繁忙、模型超时、不可用和格式错误，错误不会包含正文、密钥或原始SDK响应。

契约为 `assistant-plan-v1`，请求含 `message`、服务端北京时间 `today`、最多50个部门选项、6条历史、5个业务回执及 `allowExport`。详情以 [AI助手设计](../docs/AI助手设计.md) 为准。每次最多返回一个严格工具建议：

| 工具 | 参数 |
|---|---|
| `query_attendance_summary` | `month: yyyy-MM`、`scope: SELF/DEPARTMENT`、`deptId: null/正整数` |
| `explain_attendance_day` | `date: yyyy-MM-dd`，仅本人 |
| `create_attendance_export` | 同月度查询参数，须有导出权限且本轮尚未创建导出 |
| `get_export_status` | `jobId: 正整数`，Java再校验任务所有者 |

Python使用 LangChain Core 消息与 `ChatOpenAI.bind_tools`，关闭并行工具和自动重试，禁止额外身份字段、未知工具、重复工具和超出部门选项的建议。Java每次执行仍需重新鉴权、检查时间预算和幂等；Python不执行工具、SQL、文件操作或HTTP回调。摘要数字与执行成功状态由Java按回执生成。模型等待可取消，超时后不返回工具供Java执行。

LangSmith是LangChain Core的传递依赖，运行时显式用 `tracing_context(enabled=False)` 关闭追踪，环境误配追踪开关也不发送外部追踪。不会记录用户正文、模型原文、令牌或Key。依赖在2026-10-26核对官方PyPI后安装并锁定：`langchain-core==1.7.0`、`langchain-openai==1.7.2`、`openai==3.37.0`，保留原人脸依赖版本。未安装LangGraph或另建业务服务。

固定评测夹具为 `tests/fixtures/assistant_cases.json`，30个development与30个holdout，覆盖明确月份/范围、澄清、部门重名、越权、提示注入、导出权限、日期边界和任务编号。Mock测试只能证明规则与契约回归。真实模型必须配置后单独运行，不得把Mock通过率、Mock时延或合成SDK输出当成真实模型效果。

```powershell
# 默认仅演示规则，不调用真实模型
& ./.venv/Scripts/python.exe scripts/evaluate_assistant.py --provider mock --split all --output ../.local/assistant-mock-evaluation.json
# 仅在已配置真实模型并明确接受费用时人工执行，默认不运行
& ./.venv/Scripts/python.exe scripts/evaluate_assistant.py --provider model --split holdout --allow-paid-model --output ../.local/assistant-model-holdout.json
```

评测报告仅允许输出到项目 `.local/`，不含原始模型文本。真实模式仍须核查工具完成情况、澄清合理性、端到端权限与导出结果；固定案例计划选择不等于生产准确率。本轮没有真实模型凭据，也没有真实模型完成率、费用或时延结论。

## 模型来源与许可

模型固定来源为 [OpenCV Zoo 提交 48b10fa](https://github.com/opencv/opencv_zoo/tree/48b10fa505aeb51fbd127fc60a5c660040e823b7)。YuNet 使用 [MIT 许可](models/YUNET-LICENSE.txt)，SFace 使用 [Apache 2.0 许可](models/SFACE-LICENSE.txt)。许可原文从同一提交的对应模型目录保留，重新分发模型时一并保留。模型调用方式参考 [OpenCV 官方人脸教程](https://docs.opencv.org/4.x/d0/dd4/tutorial_dnn_face.html)。
