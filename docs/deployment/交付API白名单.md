# 交付 API 白名单

> 约定：`server.servlet.context-path=/award-log`。下文路径均为**相对 context-path** 的映射（前端 `baseURL` 已含 `/award-log`，请求写成 `/log/...` 即可）。
>
> **说明**：未列入「必须接通」的接口**不代表删除**；后端可保留、Swagger/脚本可调用。演示与默认导航**不要求**接通这些能力。Controller 上的 `@Deprecated(since = "delivery-2026-07")` / `@Tag` 仅作交付面标注，见源码。

---

## 1. 交付必须接通（有挂载 UI / 演示必用）

### 1.1 认证与个人中心

| 方法 | 路径 | 用途 |
|------|------|------|
| POST | `/admin/user/login` | 登录 |
| POST | `/admin/user/logout` | 登出 |
| POST | `/admin/user/register` | 注册（演示入口） |
| GET/PUT | `/api/profile/user-info` | 个人资料 |
| POST | `/api/profile/change-password` | 改密 |
| GET | `/api/profile/access-trail` | 访问轨迹 |
| GET/POST | `/api/profile/api-keys*` | API Key |
| GET | `/api/profile/user-stats` | 个人统计 |

### 1.2 日志分析主链路

| 方法 | 路径 | 用途 |
|------|------|------|
| POST | `/log/upload`、`/log/upload/multi` | 上传分析 |
| GET | `/log/task/{taskId}` | 任务状态轮询 |
| GET | `/log/report/{taskId}` | 报告（默认异常抽样 + 等级分布，非整表） |
| GET | `/log/report/{taskId}/details` | 明细分页（`pageNum`/`pageSize`/`anomalyOnly`） |
| GET | `/log/history` | 历史列表 |
| GET | `/log/download/{taskId}/{type}` | 报告下载 |
| POST | `/log/diagnose/{taskId}` | 诊断触发 |
| POST | `/log/cancel/{taskId}` | 取消任务 |
| DELETE | `/log/delete/{taskId}` | 删除任务 |
| POST/GET | `/log/diagnose/chat`、`/log/diagnose/stream/{taskId}` | 诊断 SSE（AiDiagnosis） |

### 1.3 统一助手 / MCP 工具台

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/api/assistant/context` | 助手上下文 |
| POST | `/api/assistant/chat/stream` | 助手 SSE 对话 |
| GET | `/api/mcp/tools` | 工具列表 |
| POST | `/api/mcp/execute` | 执行工具 |
| POST | `/api/mcp/confirmExecute` | 二次确认执行 |

### 1.4 巡检态势主链路

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/api/ops/patrol/history`、`/history/trend` | 巡检历史/趋势 |
| GET | `/api/ops/patrol/correlation/latest` | 最新关联 |
| POST | `/api/ops/patrol/run` | 手动巡检（若入口开放） |
| GET | `/api/ops/patrol/alerts/recent` | 近期告警条 |
| GET | `/api/ops/patrol/remediation/pending`、`/coverage`、`/last` | 修复提案 |
| POST | `/api/ops/patrol/remediation/confirm` | 确认修复 |
| GET | `/api/ops/effect/dashboard` | 运维效果看板 |

### 1.5 审计 / 配置 / 平台

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/api/audit/feed`、`/api/audit/detail` | 统一审计中心 |
| GET | `/api/ops-trace/recent` | LiveWall / 态势辅助（有调用） |
| GET/PUT | `/api/system-config/effective` | 系统配置中心 |
| POST | `/api/system-config/bootstrap/reconcile` | Bootstrap 对齐 |
| GET/PUT | `/api/agent/path-policy` | 路径白名单（配置中心一并使用） |
| GET | `/api/platform/info` | 环境状态 / TopBar / 欢迎页 |
| GET | `/api/security/self-check` | 安全自检弹窗 |

### 1.6 知识库 / 统计看板

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/api/v1/knowledge/status`、`/documents`、`/search` | 知识库 |
| POST | `/api/v1/knowledge/upload`、`/upload/file` | 上传 |
| DELETE | `/api/v1/knowledge/document/{documentId}` | 删文档 |
| GET | `/admin/statistics/log-summary` | Dashboard 摘要 |
| GET | `/admin/statistics/performance` | TopBar / LiveWall / 对话侧性能 |
| GET | `/admin/statistics/task-status` | 任务状态统计 |

### 1.7 实时通道（演示常用）

| 协议 | 路径 | 用途 |
|------|------|------|
| WS | `/ws/performance` | 性能推送（TopBar） |

---

## 2. 后端存在但交付可不演示（赛题扩展 / 仅 API）

> 已在对应 Controller 上标注 `@Tag`；多数另加 `@Deprecated(since = "delivery-2026-07", forRemoval = false)`（学习闭环与告警封装等仅 Tag、不 Deprecated）。

### 2.1 赛题扩展面（无挂载 UI）

| 模块 | 前缀 / 典型路径 | Controller |
|------|-----------------|------------|
| Decision | `/api/v1/decision/*` | `DecisionController` |
| Alarm Lifecycle | `/api/v1/alarm/lifecycle/*` | `AlarmLifecycleController` |
| Experiment | `/api/v1/experiment/*` | `ExperimentController` |
| Rules | `/api/v1/rules/*` | `RuleManagementController` |
| Templates | `/api/v1/templates/*` | `TemplateManagementController` |
| LLM 直连 | `/api/v1/llm/chat*` | `LlmChatController`（演示走 `/api/assistant`） |
| Perception | `/api/ops/perception/snapshot` | `PerceptionController` |
| Security Signals | `/api/security/signals/*`（含 ingest） | `SecuritySignalController`（采集面可用，无默认 UI） |
| Elasticsearch | `/api/elasticsearch/*` | `ElasticsearchController` |
| Log Clean | `/api/log/clean*` | `LogCleanController` |
| Performance 细接口 | `/api/performance/*` | `PerformanceAnalysisController`（统计演示用 `/admin/statistics/performance`） |
| Model evaluate/reload | `/api/v1/model/evaluate`、`/reload` | `ModelEvaluationController` / `ModelHealthController` |
| Model health | `GET /api/v1/model/health` | 可保留探测，无默认页 |
| Collector / Kafka | `/api/collector/status`、`/api/kafka/status` | `CollectorStatusController` / `KafkaMonitorController` |
| Runbook | `/api/runbook/*` | `RunbookApprovalController` |
| Ops Schedule | `/api/ops-schedule/tasks*` | `OpsScheduleController` |
| RBAC | `/admin/role/*`、`/admin/permission/*` | `SysRoleController` / `SysPermissionController` |

### 2.2 仅 Tag、不 Deprecated

| 模块 | 路径 | 说明 |
|------|------|------|
| Decision Feedback | `/api/decision-feedback/*` | **持续学习 API，无默认 UI** |
| Alarm Config / History / Rule Compat | `/api/alarm/*`、`/api/alarm/history/*`、`/api/alarm-rule/*` | 仅 API，无页面 |
| Autonomous Ops | `/api/ops/autonomous/*` | **巡检内部/可选**（与 patrol 主链路并存） |

### 2.3 前端有封装但无默认导航页（遗留）

| 能力 | 路径 | 备注 |
|------|------|------|
| AI 访问审计 | `/admin/audit/ai/recent` | `AiAuditCenter` 未挂载 |
| Ops Trace 详情页 | `/api/ops-trace/detail` | 中心页未挂载；`/recent` 仍被 LiveWall 使用 |
| Workflow Memory UI | `/api/ops/workflow/*` | `OpsMemoryPanel` 未挂载 |
| 用户 CRUD / 忘记密码 | `/admin/user/{id}`、`/list`、`/page`、`/check-user`、`/reset-password` | 无管理页 / 入口未闭环 |
| 日志 pause/resume | `/log/pause/{taskId}`、`/log/resume/{taskId}` | 封装有、页未用 |

---

## 3. 运维 / 探针 / 内部用路径

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/api/platform/readiness` | 就绪探针 |
| GET | `/api/platform/backend-probe` | 后端存活探针 |
| GET | `/api/platform/acceptance` | 公开验收探针（可不登录） |
| GET | `/api/v1/model/health` | 模型健康（可选巡检） |
| GET | `/api/collector/status`、`/api/kafka/status` | 中间件状态（Env 页不直打也可） |
| POST | `/api/ops/autonomous/run` | 内部自动修复触发 |
| GET | `/api/security/self-check` | 演示可用，亦属安全探针 |

---

## 4. 判定原则（摘要）

1. **必须接通** ≈ App 默认 Tab / 演示脚本会点到的页面所依赖的接口。  
2. **可不演示** ≈ 后端产能在、无默认导航；评委追问时可答「仅 API / Swagger」。  
3. **不删代码**：白名单是交付口径，不是删库删接口清单。  
4. 主链路**不要**误标 Deprecated：`LogAnalysis`、`UnifiedAssistant`、`McpExecute`、`OpsPatrol`、`Audit`、`SystemConfig`、`PlatformInfo`、`Profile`、`Knowledge`、Statistics 主三个、`SysUser` 登录等。
