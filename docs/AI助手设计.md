# AI考勤助手：查询与受控导出

日期：2026-10-26。前置为已通过217项测试的报表中心（9ecc76a）。员工、主管和管理员通过对话查询考勤及生成已有权限范围内的月报，减少手工筛选；Java仍是唯一业务权威。本轮未发现可用模型Key与模型配置，不开通付费服务，真实模型效果需配置后单独评估。

## 首版范围与非目标

提供会话隔离、运行与工具记录、LangChain模型工具选择、本人/部门月度汇总、本人单日解释、创建月报导出任务和本人导出任务状态。复用已实现的报表/日结果/导出Service，不另写规则。模糊月份、重名部门或无明确范围时先澄清，不默认全组织。界面展示来源卡片和实际执行状态；导出工具成功表示任务已创建，不表示文件已生成。

本轮不包含AI提交请假/补卡、AI审批、人脸修改、权限管理、工资、RAG、任意SQL/HTTP/命令、公开部署及SSE。先完成有持久结果、能恢复未知请求的有界同步轮次；流式与确认式申请后续扩展。常规业务不依赖AI。

## 分工与安全边界

Java持久化会话、运行和工具回执，持有当前登录身份并执行工具。Python在同一FastAPI能力进程内新增独立聊天模块，使用LangChain Core消息与 `ChatOpenAI.bind_tools` 选择结构化工具；Python不访问业务库、不持有用户JWT、不回调Java、不执行模型提出的代码。人脸与聊天分别鉴权、限流及超时，缺少人脸模型不阻止独立聊天状态。

相比原规划的Python回调工具，本版采用分轮计划：Java将当前问题、有限历史及安全工具结果发给Python，取得单个工具建议后本地验证执行，再送入下一轮计划。这样不需要额外委派凭证和回调接口，Java仅负责持久状态/权限/预算，Python只负责提示词、模型与结构化工具选择，不复制业务计算。真正的模型调用只发生在Python。

默认 `ASSISTANT_ENABLED=false`；真实模式为 `ASSISTANT_PROVIDER=openai-compatible`，需要明确的 `ASSISTANT_API_KEY`、`ASSISTANT_MODEL`，可配置受信的 `ASSISTANT_BASE_URL`。不会替用户挑付费型号或自动开通服务。`ASSISTANT_PROVIDER=mock` 必须显式设置，状态、运行、页面和测试报告均标明“演示模式，非真实AI”；模拟理解仍会经过Java执行真实业务工具，不能把Mock通过率称为模型效果。

Java/Python使用独立于人脸令牌的 `ASSISTANT_INTERNAL_TOKEN`，通过 `X-Assistant-Service-Token` 请求头传递，绝不进入模型提示、工具参数、浏览器或日志。浏览器只能访问Java。API Key只供Python读取，不回传。禁用外部LangSmith追踪，不接收用户动态回调/工具配置，不自动降级为Mock。

每次工具执行重新检查登录有效性、账号、操作权限、部门和任务所有者。客户端或模型提供的userId/role/allowedDeptIds等均拒绝或忽略，不决定身份。单轮最多5个工具、最多1个导出任务，总预算60秒；模型返回后、执行工具前再次检查时限，过期结果不得产生新写入。并发同一用户只允许一个RUNNING轮次，数据库短事务加用户行锁实现，不在模型等待期间锁用户行。

## 受控工具

工具参数严格白名单，未知工具/多工具并发/额外身份字段均拒绝。工具调用与导出创建使用稳定runId和请求编号；同轮重复或重试不能创建第二个任务，已有任务结果保留。allowExport由Java当前records.export权限产生，不是模型声明的授权。对读失败、权限拒绝及超时保留真实状态，不用模型语言把失败包装为成功。

| 名称 | 参数 | Java执行与回执 |
|---|---|---|
| query_attendance_summary | month,scope,deptId（SELF为null） | MonthlyReportService.build，返回范围、生成时间、统一statistics及固定来源，不向模型发送所有员工姓名或逐日全表 |
| explain_attendance_day | date（yyyy-MM-dd） | 仅本人，复用AttendanceService，白名单返回当日规则、事件时间/来源、异常分钟及请假结果，不返回地址/照片 |
| create_attendance_export | month,scope,deptId | ReportExportService.create，同一run固定requestKey，最多1个；复查records.export；返回真实任务id/state及到报表中心的固定来源 |
| get_export_status | jobId | 复用ReportExportService.detail，只能当前创建者读取；返回真实元数据，不返回文件或公开链接 |

总结数字、已创建/失败等业务结论由Java根据回执生成简明事实说明，模型的自由文本不能覆盖它们。工具结果来源卡片可展开核对。模型仅在没有业务回执时提供澄清/能力说明，不据此宣称业务已经执行。

## Java/Python契约

内部 `GET /assistant/health`，`POST /assistant/v1/plan`，均使用独立内部令牌。契约版本固定 `assistant-plan-v1`。POST最大200KiB，JSON严格验证；请求最多6条历史、5条本轮回执及50个受限部门选项。单个消息最多2000字，工具摘要去除不必要的个人资料。today为服务端北京时间日期；部门名称作为不可信数据传入，不能变成系统指令。

请求：`{contractVersion,message,today,departments:[{id,name}],history:[{message,answer}],steps:[{toolName,arguments,success,data}],allowExport:boolean}`。

响应：`{contractVersion,mode:"MOCK"|"MODEL",message,toolCalls:[{name,arguments}],usage:{inputTokens,outputTokens}}`。每次toolCalls长度0或1；空数组表示本轮结束或需要补充。参数不包含可信身份或任意URL。真实模型使用关闭并行工具的bind_tools，provider超时/结构错误固定返回可识别失败，不透传SDK响应或凭据。

Mock支持用于可复现演示的明确中文月份（本月/上月/今年某月/完整年月）、本人/明确部门、昨天/明确日期及任务编号；不明确或重名选项先澄清。Mock是规则解析，只用于接口和业务验证，不替代真实自然语言评估。

## 外部HTTP与页面契约

均要求登录及records.list，沿用 `{code,msg,data}`，所有会话/运行/回执严格本人隔离。

- `GET /assistant/status` → `{enabled,ready,mode,reason}`，不返回Key或内部URL。未配置不影响读取历史及普通业务。
- `GET /assistant/conversations?pageNum=&pageSize=` → `{records,total}`。
- `POST /assistant/conversations {title?}` → `{id,title,createdAt,updatedAt}`。
- `GET /assistant/conversations/{id}` → `{conversation,runs:[运行白名单及按序工具回执]}`，最多最近20轮，页面提示有界历史。
- `POST /assistant/runs {conversationId,requestKey,message}` → 运行与工具回执；同owner/requestKey同内容重试返回原运行，不同内容409；模型等待期间不占有长事务。POST客户端超时70秒，未知结果保留原三字段，不换编号。
- `GET /assistant/runs/by-request?requestKey=` → 本人原请求结果，未找到404。
- `GET /assistant/runs/{id}` → 本人运行与工具回执。

运行字段：id、conversationId、turnIndex、message、answer、state、mode、errorCode、createdAt、finishedAt、tools。工具回执：sequence、toolName、arguments、state（SUCCEEDED/FAILED）、data、errorCode、createdAt；不返回ownerId、请求hash、内部令牌或模型原始响应。运行状态RUNNING、COMPLETED、NEEDS_INPUT、FAILED、TIMED_OUT、INTERRUPTED。

Vue在考勤工作台增加“AI助手”页签，保留既有紫色Element Plus。显示配置状态、会话列表、问题/事实回答/来源卡片、真实导出任务卡和到报表中心按钮。未知结果先恢复原请求；卸载停止UI更新，不自动重发副作用。无真实配置显示可操作的配置缺失状态，Mock有持续可见标记。

## 持久化与崩溃恢复

V005增加三表：

- assistant_conversation：id bigint PK，owner_id int，title varchar(100)，created_at/updated_at datetime。
- assistant_run：id varchar(36) PK，conversation_id bigint，turn_index bigint，owner_id int，request_key varchar(64)，request_hash varchar(64)，message varchar(2000)，answer text，state varchar(20)，mode varchar(10)，error_code varchar(50)，created_at/finished_at/deadline_at datetime。owner_id/request_key及conversation_id/turn_index分别唯一，owner_id/state/deadline_at索引。用户行锁内分配会话递增turn_index，页面和模型历史均按该序号，避免同秒随机UUID造成乱序。
- assistant_tool_call：id bigint PK，run_id varchar(36)，call_order int（外部sequence），tool_name varchar(80)，arguments_json text，state varchar(20)，result_json mediumtext，error_code varchar(50)，created_at datetime。run_id/call_order唯一。

工具回执和副作用在同一短事务提交（报表create加入该事务），避免“任务创建而回执丢失”。运行完成状态另行保存。进程丢失后的RUNNING超过deadline标为INTERRUPTED，只展示已经提交的回执，不自动重放模型或工具；同请求重试返回该状态，用户可查看已有任务后开启新轮。查询或开始新轮时做此恢复，应用启动也执行恢复；不能靠再次请求自动生成第二份导出。

会话仅按run保存用户问题、事实回答和必要回执，不保存人脸、口令、Key、模型思维过程或无限上下文。后续要增加保留期/删除机制，首版本在文档明确尚未实现自动归档。

历史回执也受当前权限约束：部门汇总持久化私有范围证明，读取历史、恢复请求以及再次向模型组装上下文时复查当前人员与每日可见范围。已失去部门、人员或历史日权限的旧data及含旧数字的answer隐藏，提示重新查询；原执行状态保留。私有证明不回传浏览器或模型。同一次结果投影对相同条件只计算一次校验，不能跨模型等待长期缓存授权。

## 验收与外部资料

测试覆盖：登录/跨用户会话、工具未知及越权、模型伪造身份、跨部门与任务所有者、同请求并发幂等、单用户运行限制、事务失败无重复导出、超时后不执行写入、原请求恢复、进程中断保留回执、Mock显式标识、模型故障不影响人脸。使用合成库做Java→Python LangChain→Java工具→MySQL导出→鉴权下载链路与桌面/手机检查。

真实模型评测另建至少60个固定案例，区分开发/保留集及真实MODEL/Mock；没有真实凭据时不得给出真实完成率、时延、费用或“已接通真实AI”的结论。

官方资料核对于2026-10-26：[LangChain模型与工具调用](https://docs.langchain.com/oss/python/langchain/models)、[ChatOpenAI集成](https://docs.langchain.com/oss/python/integrations/chat/openai)、[OpenAI函数调用](https://developers.openai.com/api/docs/guides/function-calling)。只使用必要的LangChain Core与Provider集成并锁定实际安装版本，不额外安装LangGraph平台。
