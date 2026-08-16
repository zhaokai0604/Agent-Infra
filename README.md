# ThreshCore / award-log

ThreshCore 是一个面向企业运维的日志分析与可控 Agent 平台，整合日志诊断、主机巡检、MCP 工具调用、风险门控、自动处置、审计追踪和知识记忆。

项目重点不是让模型获得一个无限制终端，而是让 Agent 在明确的工具白名单、参数校验、风险分级、人工确认和结果验证边界内完成运维任务。

## 当前状态

| 项目 | 说明 |
|---|---|
| 后端 | Java 17、Spring Boot、MyBatis、Spring AI |
| 前端 | Vue 3、Vite、Element Plus、ECharts |
| 数据库 | MySQL 8 默认；MariaDB 可通过配置切换 |
| 默认后端端口 | `8088` |
| 前端开发端口 | `3000`，由 Vite 代理到后端 `8088` |
| 上下文路径 | `/award-log` |
| 可选依赖 | Qdrant、Elasticsearch、Kafka、Redis |
| 主要入口 | `http://localhost:3000` 或 `http://localhost:8088/award-log/` |

## 功能说明

| 功能 | 使用入口 | 核心实现 | 备注 |
|---|---|---|---|
| 日志分析 | 前端“日志分析” | `LogAnalysisController`、`LogAnalysisService` | 支持上传、解析、异常识别、报告和导出 |
| Agent 对话 | 前端“Agent 对话” | `AssistantOrchestrator`、`OpsRuntimeService` | 先识别意图，再选择 Playbook、Skill 和工具 |
| 主机巡检 | 前端“巡检” | `OpsPatrolController`、`OpsPatrolService` | 汇总磁盘、CPU、内存、进程、服务和网络状态 |
| 自动处置 | 巡检结果或 Agent 对话 | `OpsAutoRemediationService` | 生成方案后按风险决定自动执行、确认或阻断 |
| MCP 工具 | 前端“MCP 工具控制台” | `McpToolCatalog`、`McpToolDispatcher` | 工具注册、参数解析、统一调用和结果标准化 |
| 安全门控 | 所有写操作 | `McpInvocationSecurityGate`、`OpsGovernanceService` | 默认只读；高风险操作需要确认或直接阻断 |
| 审计追踪 | “审计”“运维链路” | `AuditController`、`OpsTraceController` | 保存用户、Agent、工具、参数摘要、风险和执行结果 |
| AWM 记忆 | “工作流记忆” | `WorkflowMemoryService`、`WorkflowInductionService` | 只沉淀验证成功的处置流程，供后续任务复用 |
| Reflexion | “安全教训” | `FailureInsightService` | 保存拦截、失败、证据不足和后续改进建议 |
| 知识库 RAG | “知识库” | `KnowledgeController`、`KnowledgeBaseService` | 文档切分、向量检索和历史案例辅助诊断 |
| 安全信号 | 安全态势相关页面 | `SecuritySignalService` | 接收、归一化和汇总网络、主机及进程类信号 |
| 系统配置 | “系统配置” | `SystemConfigController`、配置类 | 集中管理路径、风险、巡检和自动处置策略 |
| 效果评估 | “运维效果” | `OpsEffectDashboardService` | 对处置前后指标、执行质量和恢复结果进行对比 |

更完整的功能备注见 [`docs/功能说明.md`](docs/功能说明.md)。

## 系统架构

```text
用户 / 告警 / 日志
        ↓
前端控制台与 API
        ↓
意图识别 → Agent 编排 → Skill / Playbook
        ↓
MCP 工具目录 → 参数校验 → 风险门控 → 执行
        ↓
结果验证 → 审计追踪 → AWM / Reflexion / RAG
```

后端主要目录：

```text
src/main/java/com/award/log/
├─ agent/          Agent 编排、运行时、记忆和巡检
├─ controller/     HTTP API
├─ mcp/            工具目录、分发器和工具实现
├─ security/       风险识别、权限和安全门
├─ governance/     运维治理和处置策略
├─ service/        业务服务
└─ config/         Spring 与运行配置
```

前端主要目录：

```text
frontend/src/
├─ components/     页面组件
├─ components/agent/ Agent 工作台组件
├─ api/             API 封装
├─ composables/     组合式逻辑
└─ utils/           审计、MCP、导出和展示工具
```

## 快速开始

### 环境要求

- JDK 17
- Maven 3.8+
- Node.js 18+
- MySQL 8 或 MariaDB 10.11+
- 可选：Docker Desktop、Qdrant、Elasticsearch、Kafka、Redis

### 1. 准备数据库

```sql
CREATE DATABASE IF NOT EXISTS log_analysis
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

执行初始化脚本：

```powershell
mariadb -u root -p log_analysis < src/main/resources/schema.sql
```

也可以启动本地依赖：

```powershell
Copy-Item .env.example .env
# 编辑 .env，至少设置 MYSQL_ROOT_PASSWORD
docker compose up -d mysql qdrant
```

### 2. 配置本地参数

```powershell
Copy-Item src/main/resources/application-local.example.yml src/main/resources/application-local.yml
```

在本地配置文件或环境变量中填写数据库密码和模型密钥。以下文件不得提交：

- `.env`
- `application-local.yml`
- 真实数据库密码、API Key、Token 和生产地址

### 3. 启动后端

```powershell
mvn spring-boot:run
```

后端默认地址：

- 业务入口：`http://localhost:8088/award-log/`
- API 文档：`http://localhost:8088/award-log/doc.html`
- 健康检查：`http://localhost:8088/award-log/actuator/health`

### 4. 启动前端

```powershell
cd frontend
npm install
npm run dev
```

浏览器访问：`http://localhost:3000`。

## 常用 API

| 目的 | 方法 | 路径 |
|---|---|---|
| 查看平台状态 | `GET` | `/award-log/api/platform/info` |
| 查看工具目录 | `GET` | `/award-log/api/mcp/tools` |
| 执行工具 | `POST` | `/award-log/api/mcp/execute` |
| 确认写操作 | `POST` | `/award-log/api/mcp/confirmExecute` |
| Agent 流式对话 | `POST` | `/award-log/api/assistant/chat/stream` |
| 启动巡检 | `POST` | `/award-log/api/ops/patrol/run` |
| 查看运维链路 | `GET` | `/award-log/api/ops-trace/recent` |
| 查看工作流记忆 | `GET` | `/award-log/api/ops/workflow/memory` |
| 查看安全自检 | `GET` | `/award-log/api/security/self-check` |

## 安全边界

- 工具必须注册后才能被调用。
- 参数、路径、权限和风险等级会在执行前检查。
- 只读查询优先自动执行，写操作默认先预览。
- 中风险操作需要用户确认，高风险或不可回滚操作直接阻断。
- 执行结果必须进入审计链路，并尽可能进行前后状态验证。
- 不要把真实密钥、生产配置、用户数据或运行时目录提交到 Git。

## 构建与测试

后端：

```powershell
mvn test
mvn verify
```

前端：

```powershell
cd frontend
npm run build
```

推荐提交前执行：

```powershell
mvn verify
cd frontend
npm run build
```

测试使用 H2 和测试配置，不要求本地启动完整中间件。生产环境仍需按实际部署方式配置数据库、模型和可选组件。

## 运行排障

1. 后端是否监听 `8088`。
2. 数据库是否已创建并导入 `schema.sql`。
3. 是否误提交或误加载了 `application-local.yml`。
4. 前端代理是否仍指向 `8088`。
5. 工具调用是否被权限、路径策略、风险门或确认流程拦截。
6. 修改数据库结构后是否同步测试 Schema 和 Mapper。

## 项目文档

- [功能说明](docs/功能说明.md)
- [部署文档](docs/deployment/部署文档.md)
- [部署指南](部署指南.md)
- [架构图](docs/architecture/)
- [版权与使用限制](版权声明与使用限制声明.md)

## 维护规则

- 源码变更同步更新对应功能备注。
- 新增 API、工具或前端页面时，补充 `docs/功能说明.md`。
- 修改端口、环境变量、启动步骤时，同步更新 README。
- 生成物、缓存、日志、测试结果和本地运行目录不进入仓库。
