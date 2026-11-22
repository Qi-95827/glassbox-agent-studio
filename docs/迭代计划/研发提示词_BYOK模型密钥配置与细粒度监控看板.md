# 研发任务提示词与验收标准：用户自带模型密钥（BYOK）与细粒度监控看板

> **文档定位**：本文档可直接作为 **Prompt（研发指令）** 投喂给其他 AI Coding Agent 执行开发。  
> **推荐执行方式**：建议将 **任务 A（BYOK 模型与 API Key）** 与 **任务 B（类 Grafana 细粒度监控看板与快捷排查向导）** 分成**两个独立会话**依次执行，每完成一个任务即跑全量测试并做一次 Git 提交，能显著降低上下文负担并提升代码质量。

---

## 0. 全局项目上下文与红线约束（投喂时请务必连同本节一起复制）

你正在参与开发 **Agent Studio（可视化代码开发 Agent 工作台）**，本地仓库路径为：
`D:\software program\AI\agent-dev-workbench`

### 0.1 技术栈与目录结构
- **后端**：Spring Boot 3.5.7 / Java 21 / Spring Data JPA / H2 文件库 / Flyway / AgentScope Java 2.0.4
  - **注意**：`pom.xml` 位于**仓库根目录**，所有 `mvn` 命令必须在仓库根目录执行，**切勿在 `backend/` 目录运行 `mvn`**。
- **前端**：React 19 / Vite / MUI（Google Material Design 3 风格）/ React Flow / React Query / React Router / Vitest
  - 前端路径：`frontend/user-workbench/`
  - 构建产物 `frontend/user-workbench/dist/` 会在 Maven `process-resources` 阶段自动复制到 `target/classes/static/`。

### 0.2 必须遵守的架构与工程红线
1. **ArchUnit 架构守卫（`ArchitectureGuardTest`）**：
   - 顶层包（`dev.workbench.*`）**严禁循环依赖**。
   - `dev.workbench.domain` 与 `dev.workbench.framework` 必须保持纯净（不依赖 `persistence` / `web` / `io.agentscope`）。
   - `io.agentscope.*` SDK 调用**只允许**出现在 `dev.workbench.infrastructure`、`dev.workbench.persistence.agentscope`、`dev.workbench.app`。
   - Controller 层禁止直接依赖 JPA Repository，必须通过 Service 层调用。
2. **Flyway 数据库迁移兼容（`FlywayMigrationCompatibilityTest`）**：
   - 当前最新迁移脚本为 `V4__glassbox_trace_payloads.sql`。
   - 如新增数据库表或索引，请按序号创建 `V5__*.sql`（或后续版本），**并同步更新 `FlywayMigrationCompatibilityTest` 中的版本号与表数量断言**，确保测试通过。
3. **安全与编码规范**：
   - **API Key 严禁明文落库、严禁明文返回前端、严禁打印到日志或玻璃盒 Trace 中**（必须经服务端加密存储 + 前端仅展示掩码 + 复用 `SensitiveDataMasker` 脱敏）。
   - 前端**严禁使用 `dangerouslySetInnerHTML`**；所有组件使用 React 函数组件；复用的状态逻辑抽离为自定义 Hook。
   - 所有注释、文档、界面文案使用**简体中文**；代码标识符使用英文，禁止拼音。
   - 严禁显式 `new Thread()` 或在核心路径使用无界 `Thread.sleep`。
   - 必须保持既有 API 字段向后兼容（不能破坏历史运行记录、模拟模型 `mock` 以及现有单测）。

---

## 任务 A：用户自带模型与 API Key（BYOK）配置体系重构

> **可直接复制以下整段内容（配合第 0 节全局上下文）交给 AI Agent 执行。**

### A.1 背景与要解决的问题
当前项目的模型接入方式存在以下体验与设计不合理之处：
1. 只能手动在服务器本地创建 `config/deepseek-api-key.txt` 文件来使用 `deepseek-flash`，普通用户无法在前端界面填写自己的 API Key。
2. 设置页（`frontend/user-workbench/src/features/settings/SettingsPage.jsx`）中只展示了「费用价格版本」（`CostPricePanel.jsx`），用户既不能配置模型提供商和 API Key，却被要求去维护平台价格表，产品定位割裂。

**重构目标**：建立清晰的 **BYOK（Bring Your Own Key，用户自带密钥）模型连接器体系**：
- 用户在「设置 → 模型与 API Key」中**自主选择模型提供商、填写自己的 API Key、指定 Model ID / Base URL**，直接走用户自己的模型额度与流量。
- 保留内置免 Key 的 **模拟模型（`mock`）** 用于教学与快速体验，并向后兼容本地 `config/deepseek-api-key.txt` 兜底。
- 「参考单价」改为**模型连接上的可选附属配置**（用于工作台内部的预算上限控制与估算账本，不再让用户误以为是在给平台充值定价）。

### A.2 现有关键文件参考
- 后端：
  - `backend/src/main/java/dev/workbench/infrastructure/model/DeepSeekModelFactory.java`（已使用 AgentScope `OpenAIChatModel.builder().apiKey(...).baseUrl(...).modelName(...)`，可直接扩展支持所有 OpenAI 兼容协议模型）
  - `backend/src/main/java/dev/workbench/infrastructure/model/BuiltInModelProviders.java`
  - `backend/src/main/java/dev/workbench/infrastructure/model/DeepSeekPlanCandidateModel.java`
  - `backend/src/main/java/dev/workbench/infrastructure/model/TaskIdeaModel.java`
  - `backend/src/main/java/dev/workbench/framework/trace/SensitiveDataMasker.java`
  - `backend/src/main/java/dev/workbench/persistence/entity/ProviderPriceVersionEntity.java`
- 前端：
  - `frontend/user-workbench/src/features/settings/SettingsPage.jsx`
  - `frontend/user-workbench/src/features/workflows/CostPricePanel.jsx`
  - `frontend/user-workbench/src/features/tasks/TaskComposer.jsx`
  - `frontend/user-workbench/src/features/tasks/PlanCandidateDialog.jsx`
  - `frontend/user-workbench/src/constants/labels.js`

### A.3 后端详细设计要求

1. **数据库表设计（Flyway `V5__user_model_connections.sql`）**
   新建表 `user_model_connections`（按当前登录用户 `owner_user_id` 隔离）：
   - `id` (VARCHAR PK)
   - `owner_user_id` (VARCHAR NOT NULL)
   - `display_name` (VARCHAR NOT NULL，用户自定义展示名，如“我的 DeepSeek”)
   - `provider_type` (VARCHAR NOT NULL：`DEEPSEEK` | `OPENAI` | `QWEN` | `ZHIPU` | `SILICONFLOW` | `CUSTOM_OPENAI`)
   - `base_url` (VARCHAR NOT NULL，如 `https://api.deepseek.com`)
   - `model_name` (VARCHAR NOT NULL，如 `deepseek-chat` / `deepseek-reasoner` / `gpt-4o-mini` / `qwen-plus`)
   - `encrypted_api_key` (TEXT NOT NULL，服务端 AES-256-GCM 密文，Base64 编码含 IV)
   - `key_masked` (VARCHAR NOT NULL，如 `sk-****3f9a`，仅用于列表回显)
   - `enabled` (BOOLEAN NOT NULL DEFAULT TRUE)
   - `is_default` (BOOLEAN NOT NULL DEFAULT FALSE)
   - `input_price_per_1m` (DECIMAL / DOUBLE NULLABLE，可选参考输入单价，用于预算估算)
   - `output_price_per_1m` (DECIMAL / DOUBLE NULLABLE，可选参考输出单价，用于预算估算)
   - `currency` (VARCHAR DEFAULT 'CNY')
   - `last_test_status` (VARCHAR NULLABLE：`OK` | `FAILED`)
   - `last_test_message` (VARCHAR NULLABLE)
   - `last_tested_at` (TIMESTAMP NULLABLE)
   - `created_at` / `updated_at` (TIMESTAMP NOT NULL)

2. **API Key 加密与安全边界**
   - 新增服务端对称加密工具（如 `SecretCipher`），采用 **AES-256-GCM**（随机 12 字节 IV）。
   - 主密钥来源优先级：环境变量 `AGENT_WORKBENCH_SECRET_KEY` → 本地文件 `config/master.key`（首次运行时如不存在则自动生成随机 32 字节密钥并写入，且确保 `config/master.key` 已在 `.gitignore` 中被忽略）。
   - 任何 API 响应、异常堆栈、日志、`ModelCallPayloadEntity`、事件流中**绝对不能出现明文 API Key**。

3. **REST API 契约（Controller 层不直接操作 Repository）**
   - `GET /api/model-connections/presets`：返回内置服务商预设（DeepSeek、OpenAI、通义千问 Qwen、智谱 GLM、硅基流动、自定义 OpenAI 兼容），包含默认 `baseUrl`、推荐模型列表与文档说明。
   - `GET /api/model-connections`：返回当前登录用户的所有模型连接（仅含 `keyMasked`，不含明文/密文）+ 系统内置项（`mock` 模拟模型；若本机存在 `config/deepseek-api-key.txt`，以只读兼容项提示）。
   - `POST /api/model-connections`：新增模型连接（校验 URL 合法性、屏蔽内网 SSRF 风险或仅允许 `http://127.0.0.1` / `https://`、加密存储 Key、掩码生成）。
   - `PUT /api/model-connections/{id}`：更新配置（`apiKey` 为空时保留原密文不覆盖；非空时重新加密并更新掩码）。
   - `DELETE /api/model-connections/{id}`：删除连接（校验归属权）。
   - `POST /api/model-connections/{id}/set-default`：设为当前用户的默认模型。
   - `POST /api/model-connections/{id}/test`（及未保存前草稿测试 `POST /api/model-connections/test-draft`）：使用当前配置发一次极小 Token（如 `max_tokens=8`）的真实连通性探测，返回延迟（ms）、成功/失败状态与脱敏后的错误原因（如 401 密钥无效 / 404 模型不存在 / 429 限流 / 连接超时）。

4. **运行时模型路由与执行联动**
   - 扩展模型工厂与 `BuiltInModelProviders`：支持通过 `connectionId`（或兼容旧 `modelMode` 如 `mock` / `deepseek_flash`）动态构造 AgentScope `OpenAIChatModel`：
     - `providerType == DEEPSEEK` 时使用 `DeepSeekFormatter`；其他 OpenAI 兼容服务商使用标准 OpenAI Formatter。
     - 请求发起前才在内存中解密当前用户的 API Key，用完即丢，不缓存明文到持久化层。
   - 任务创建、想法整理（`TaskIdeaService`）、候选计划生成（`PlanCandidateGenerationService`）、节点执行均支持传入选定的 `modelConnectionId`；若未传则自动回退到用户默认连接或 `mock`。
   - 费用估算账本：若用户填了参考单价，按参考单价计算估算费用并标注「用户自备 Key（参考估算）」；未填单价时仍完整统计 Token，费用显示“未设参考单价（不扣平台费用）”。

### A.4 前端详细设计要求
1. **设置页重构（`SettingsPage.jsx`）**：
   - 新增核心卡片 **「模型与 API Key（BYOK）」**（置于账号卡片下方、最显眼位置）。
   - 列表展示用户已配置的模型连接：服务商标签、模型名、脱敏 Key（`sk-****1234`）、默认徽章、最近连通性测试结果（绿色已连通 / 红色失败 + 原因）、编辑 / 测试连通性 / 设为默认 / 删除按钮。
   - 新建/编辑弹窗（`ModelConnectionDialog.jsx`）：
     - 选择服务商预设后自动填充默认 `Base URL` 与推荐 `Model ID` 下拉/输入框；
     - API Key 密码输入框（带显示/隐藏切换，编辑态留空表示“不修改现有密钥”）；
     - 弹窗内支持 **「测试连通性」** 按钮，实时反馈耗时与结果；
     - 折叠区「可选：参考单价（仅用于本机预算上限估算）」，把原独立突兀的价格表弱化为可选高级项，消除“为什么我要给平台定价”的困惑。
2. **任务发起与规划联动**：
   - `TaskComposer.jsx`、`TaskIdeaDialog.jsx`、`PlanCandidateDialog.jsx` 的模型选择器改为动态拉取 `/api/model-connections`，清晰分组显示：**「我的模型（自带 Key）」** 与 **「内置免 Key 模型（模拟模型）」**。
   - 若用户尚未配置任何自带 Key 模型，在模型下拉底部提供快捷入口 **「+ 配置我的 API Key」**，一键跳转设置页。

### A.5 任务 A 验收标准（Checklist）
- [ ] 新用户无需手动编辑 `config/deepseek-api-key.txt`，可直接在「设置」页添加 DeepSeek 或任意 OpenAI 兼容模型的 API Key 并点击「测试连通性」通过。
- [ ] 数据库中 `encrypted_api_key` 为密文，接口响应与浏览器 Network 面板中**完全看不到明文 Key**（仅 `sk-****xxxx`）。
- [ ] 编辑模型名称或单价时不重新输入 API Key，原密钥保持可用。
- [ ] 在工作台首页选择自己配置的模型发起真实任务（含想法整理、计划生成、节点执行），全流程走用户自己的 API Key 正常跑通，且玻璃盒 `模型 I/O` 中无明文 Key 泄露。
- [ ] 历史运行记录、内置 `mock` 模拟模型、教学演示剧本 100% 兼容不回归。
- [ ] 后端新增单元测试覆盖：AES-GCM 加解密、Key 脱敏、归属权隔离（A 用户无法查看/使用/删除 B 用户的 Key）、`ArchitectureGuardTest` 与 `FlywayMigrationCompatibilityTest` 全绿。
- [ ] 前端新增/更新单元测试，`npm run format:check`、`npm run lint`、`npm test`、`npm run build` 全绿。

---

## 任务 B：类 Grafana 细粒度运行监控看板 & 快捷故障排查向导

> **可直接复制以下整段内容（配合第 0 节全局上下文）交给 AI Agent 执行。**

### B.1 背景与要解决的问题
当前「评测与监控」页的监控模块（`MonitoringPanel.jsx` + `MonitoringService.java`）仅展示少量粗粒度系统计数，存在两个明显短板：
1. 缺少针对**每次运行、每个节点、每个模型、每个工具**的细粒度可观测指标与直观可视化（类似 Grafana 的 KPI 指标卡、时序趋势图、占比圆盘/仪表盘、Top 慢节点与高消耗排行表）。
2. 当运行出现**失败、阻塞、工具被安检门拦截、模型超时/报错或高耗时**时，缺少清晰引导用户的**快捷排查顺序（Troubleshooting Playbook）**，用户不知道应该先看哪里、再看哪里。

### B.2 现有关键文件参考
- 后端：
  - `backend/src/main/java/dev/workbench/observability/MonitoringController.java`
  - `backend/src/main/java/dev/workbench/observability/MonitoringService.java`
  - `backend/src/main/java/dev/workbench/observability/RunMetricsController.java`
  - `backend/src/main/java/dev/workbench/observability/RunMetricsService.java`
  - `backend/src/main/java/dev/workbench/persistence/repository/ObservabilityRepository.java`
  - `backend/src/main/java/dev/workbench/persistence/entity/ModelInvocationEntity.java`
  - `backend/src/main/java/dev/workbench/persistence/entity/ModelCallPayloadEntity.java`
  - `backend/src/main/java/dev/workbench/persistence/repository/ModelCallPayloadRepository.java`
- 前端：
  - `frontend/user-workbench/src/features/evaluation/MonitoringPanel.jsx`
  - `frontend/user-workbench/src/features/evaluation/EvaluationsPage.jsx`
  - `frontend/user-workbench/src/features/runs/RunDetailPage.jsx`
  - `frontend/user-workbench/src/features/runs/NodeGlassboxDialog.jsx`（宽屏玻璃盒透视弹窗，支持 `result` / `handoff` / `gateway` / `llm` / `assembly` 5 个维度）

### B.3 后端详细设计要求

1. **细粒度监控看板聚合接口：`GET /api/monitoring/dashboard`**
   - 查询参数：`range`（`24h` | `7d` | `30d` | `all`，默认 `7d`）、`status`（可选状态筛选）、`model`（可选模型筛选）。
   - 按当前登录用户归属过滤数据，返回结构化 JSON（全部使用明确 Record / DTO，禁止返回模糊 `Map<String, Object>`）：
     - `summary`（顶部 KPI）：
       - `totalRuns`、`completedRuns`、`deliveredRuns`、`failedRuns`、`blockedRuns`、`successRate`（百分比）
       - `avgRunDurationMs`、`p95RunDurationMs`
       - `totalModelCalls`、`totalInputTokens`、`totalOutputTokens`、`estimatedCost`
       - `totalToolCalls`、`toolDenyCount`、`toolDenyRate`
     - `timeSeries`（时序趋势数组，24h 按小时分桶，7d/30d 按天分桶）：
       - `bucketLabel`、`runCount`、`successCount`、`failedCount`、`avgDurationMs`、`totalTokens`
     - `distributions`（圆盘 / Donut 图数据）：
       - `taskStatusBreakdown`（按 `TaskStatus` 分布：已完成 / 已交付 / 待验收 / 进行中 / 阻塞 / 失败 / 已取消）
       - `modelBreakdown`（按模型名统计：调用次数、总 Token、平均延迟、失败次数）
       - `toolBreakdown`（按工具类型统计：调用次数、通过数、拦截/失败数、平均耗时）
       - `gatewayDenyBreakdown`（按被拦截的 ToolGateway 安检门 `gate` 统计拦截次数，便于识别高频安全策略触发点）
     - `rankings`（细粒度明细与 Top 排行）：
       - `recentRuns`（最近运行明细表：runId、任务标题、TaskStatus、RunStatus、模型、节点数、耗时、输入/输出 Token、工具调用/拦截数、是否存在异常标记 `hasAnomaly`）
       - `slowestNodes`（Top 10 慢节点：runId、nodeId、节点名、角色、模型、耗时）
       - `highTokenRuns`（Top 10 高 Token 运行）

2. **单次运行自动故障诊断与排查顺序接口：`GET /api/monitoring/runs/{runId}/diagnostics`**
   - 针对任意一次运行（特别是失败、阻塞、存在工具拦截或超慢节点的运行），由服务端基于持久化证据自动分析并返回：
     - `healthLevel`：`HEALTHY` | `WARNING` | `CRITICAL`
     - `primaryIssue`：一句话核心定位结论（例如：“节点「实现单文件番茄钟」在 `PATH_WHITELIST` 安检门被拦截 2 次，导致未能写入目标文件” 或 “模型调用返回 429 限流，触发受控停止”）
     - `troubleshootingSteps`（有序排查向导列表，每步包含）：
       - `stepOrder`（1..5）
       - `title`（如“步骤 1：核对失败/阻塞节点与终态原因”）
       - `status`（`ISSUE_FOUND` | `CHECK_RECOMMENDED` | `LOOKS_OK`）
       - `finding`（服务端结合本次运行证据给出的真实排查线索）
       - `actionLabel`（快捷跳转文案，如“打开该节点的工具安检门”）
       - `targetRoute`（前端路由，如 `/runs/{runId}?nodeId={nodeId}&glassbox=gateway`，支持点击一键直达对应节点的宽屏透视弹窗具体 Tab！）
   - 排查顺序遵循标准 5 步法（自动把命中的问题步骤置顶高亮）：
     1. **运行终态与异常节点定位**（检查 `RunStatus`、`reasonCode`、失败/阻塞节点）
     2. **ToolGateway 安全门与工具调用核查**（检查 `DENY` / `FAILED` 工具调用及具体拦截门）
     3. **模型 I/O、Token 与限流/超时核查**（检查模型报错、429/401、超慢调用或截断）
     4. **上游交接完整性与装配权限漏斗核查**（检查是否缺少写工具权限或上游未产生预期产物）
     5. **工作区差异与 Git 交付状态核查**（检查是否有未提交差异、版本冲突或待审批项）

### B.4 前端详细设计要求

1. **类 Grafana 细粒度监控看板（重构 `MonitoringPanel.jsx`）**
   - 视觉风格保持与现有 Google Material Design 3 浅色主题一致（干净的白底卡片、清晰边框、克制语义色），兼顾 Grafana 仪表盘的专业信息层次与可读性。
   - **顶部控制条**：时间范围分段切换（`近 24 小时` / `近 7 天` / `近 30 天` / `全部`）+ 手动刷新按钮 + 自动刷新开关（30s 轮询）。
   - **第一层：KPI 指标概览行（5~6 张卡片）**：
     - 任务成功率（带小型圆弧仪表盘 Gauge）
     - 总运行数 / 异常数（点击异常数可快速筛选异常运行）
     - 平均耗时 / P95 耗时
     - 总 Token 消耗（Input / Output 拆分）
     - ToolGateway 拦截次数与拦截率
   - **第二层：可视化图表区（推荐零重型依赖的轻量纯 React SVG 图表组件，或评估引入 `recharts`；若手写 SVG 需带 hover Tooltip 与图例）**：
     - **左侧宽卡（时序趋势图）**：切换查看「运行量（成功/失败堆叠柱状）」「平均耗时趋势（折线）」「Token 消耗趋势（面积/柱状）」。
     - **右侧圆盘卡（Donut 圆环图切换）**：切换查看「任务状态分布」「模型调用/Token 占比」「工具调用分布与拦截占比」。
   - **第三层：细粒度运行监控表 + 慢节点 / 拦截排行**：
     - 可按「全部 / 仅看异常」筛选的细粒度运行表：展示任务名、双层状态、模型、耗时、Token（In/Out）、工具（通过/拦截）、快捷操作（**「一键排查」** / **「查看运行」**）。
     - 并排展示「Top 慢节点」与「ToolGateway 高频拦截安检门」排行卡片，点击条目可直接跳转到对应运行的对应节点。

2. **快捷故障排查向导（`RunDiagnosticsDrawer.jsx`）**
   - 在监控看板的运行表中点击 **「一键排查」**，或在运行详情页（`RunDetailPage.jsx`）出现失败/阻塞/工具拦截警告时点击 **「快速排查」**，右侧滑出（或居中弹出）诊断向导：
     - 顶部卡片：健康等级徽章 + 服务端给出的 `primaryIssue` 一句话根因定位。
     - 下方按 `1 → 5` 编号的**步骤式排查流（Stepper / Checklist）**：
       - 命中问题的步骤用红/橙色高亮展开，正常的步骤用绿色折叠；
       - 每一步右侧提供 **一键直达按钮**（例如「查看工具安检门」「查看模型 I/O」「查看装配配方」「去工作区核对」），点击后直接导航到 `RunDetailPage` 并自动选中目标节点、自动打开 `NodeGlassboxDialog` 对应的 Tab！
   - 为此需同步让 `RunDetailPage.jsx` 与 `NodeInspector.jsx` 支持读取 URL Query 参数 `?nodeId=...&glassbox=gateway|llm|handoff|assembly|result` 自动展开目标节点的宽屏透视弹窗。

### B.5 任务 B 验收标准（Checklist）
- [ ] 监控看板完整展示：时间范围切换、KPI 卡片、至少 1 个时序趋势图、至少 2 个圆盘（Donut/Gauge）分布图、细粒度运行明细表与慢节点/拦截排行。
- [ ] 无数据时（空库或该时间段无运行）有优雅的空态提示，不出现 `NaN`、`Infinity` 或图表崩溃。
- [ ] 对一次存在工具拦截（如教学剧本 3 `ToolGateway 限额与安检演示`）或模拟失败的运行点击 **「一键排查」**，能正确定位到具体的失败/拦截节点与安检门，并按 1~5 步给出清晰排查线索。
- [ ] 点击排查步骤中的快捷按钮，能够一键跳转到运行详情页并**自动定位到对应节点、自动弹出宽屏玻璃盒弹窗并切到对应 Tab**。
- [ ] 后端新增单元测试覆盖：`dashboard` 聚合统计（含空库、多模型、含拦截场景）与 `diagnostics` 5 步故障定位逻辑；`ArchitectureGuardTest` 全绿。
- [ ] 前端新增图表与排查向导单元测试，`npm run format:check`、`npm run lint`、`npm test`、`npm run build` 全绿。

---

## 最终交付自检命令（每个任务完成后必须执行）

```powershell
# 1. 前端规范、测试与生产构建
cd frontend/user-workbench
npm run format
npm run lint
npm test
npm run build
cd ../..

# 2. 后端全量测试（含 ArchUnit 架构守卫与 Flyway 迁移兼容测试）
mvn test
```

> 要求：全部命令退出码为 `0` 后，方可汇报完成，并附上改动文件清单、验证结果与人工测试建议。
