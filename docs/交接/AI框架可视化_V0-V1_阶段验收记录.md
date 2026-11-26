# AI 框架可视化演进 · V0–V1 阶段验收记录

> **记录日期**：2026-11-26
> **代码仓库**：`D:\software program\AI\agent-dev-workbench`
> **基线**：`d70a9a4`（本轮开工前）
> **本轮产出**：`cfa7109`（V0）→ `b300b8c`（V1）
> **当前状态**：`master` 本地领先 `origin/master` 6 个提交，**均未推送远端**

---

## 〇、状态声明（请先读这一节）

本文件是**阶段性**验收记录，不是 V0–V6 的最终验收记录。

| 阶段 | 状态 | 说明 |
| :--- | :--- | :--- |
| **V0 可视化前端地基轻量化** | ✅ 已完成并实跑验证 | 已提交 `cfa7109` |
| **V1 活的玻璃盒（真流式 + ReAct 泳道 + 安检流水线）** | ✅ 已完成并实跑验证 | 已提交 `b300b8c` |
| V2 上下文压缩显微镜 + Hybrid RAG / 三层记忆透视 | ❌ 未实施 | 见第六节的范围与入口说明 |
| V3 可视化多 Agent 编排画布 | ❌ 未实施 | 同上 |
| V4 时间轴火焰图 + Checkpoint 时间旅行 / What-If 分叉 | ❌ 未实施 | 同上 |
| V5 动态实时架构 X 光图 | ❌ 未实施 | 同上 |
| V6 OTel 导出 / 外部 Trace 接入 / 评测飞轮 | ❌ 未实施 | 同上 |

**未完成的部分没有任何占位实现、假数据或空壳接口。** 本文件与代码中都不存在「声称已完成但实际未实现」的能力描述；第五节的数字全部来自本轮真实执行的命令输出。

---

## 一、V0：可视化前端地基轻量化与运行态协同修复

### 1.1 设计要点

1. **首屏减重**：`App.jsx` 的 10 个路由页全部改为 `React.lazy`。4,300+ 行的 `ArchitecturePage`（及其 123 kB 的 `ArchitectureCanvas`）从首屏主包拆成独立 chunk，只有进入 `/architecture` 才拉取。
2. **三级错误边界**：
   - `main.jsx` 最外层（兜住应用壳自身，位于 `I18nProvider` 之内以保证降级文案按当前语言渲染）；
   - `WorkbenchShell` 内层（错误边界 → Suspense → Outlet），单页渲染或 chunk 加载失败时只降级主区域，导航仍可用，并随 `location.key` 复位；
   - 画布与抽屉局部（`RunFlowGraph`、`NodeInspector`、`ToolApprovalDrawer`），单组件报错不再白屏。
3. **SSE 与轮询彻底协同**（`useRunExecution.js`）：
   - `pollingInterval(status, streamConnected, visible)` 成为唯一判据：运行非活跃、SSE 已连通、标签页不可见，任一成立即关闭定时器；
   - **服务端状态未知时不算「运行中」**——否则首屏会为早已结束的运行建立一条被服务端立即关闭的空连接；
   - `RUN_EVENT` 触发的 `invalidateQueries` 增加 **280ms** 防抖合并；
   - 推理增量（`MODEL_REASONING_DELTA`）与正文增量走独立通道。
4. **画布重绘收敛**：`FlowNode` 用 `React.memo` + 显式比较器，回调经 `useCallback` 稳定，`graphNodes`/`graphEdges` 用 `useMemo`；React Query 的结构共享保证未变化的节点对象保持同一引用，因此按引用比较即可。
5. **长事件列表**：新增自研固定行高窗口虚拟化 `VirtualList`（~110 行），千级事件只渲染可视行 + overscan，不引入重型依赖。
6. **结构化错误码**：后端 401/403/管理员限制响应补充与语言无关的 `errorCode`（`AUTH_REQUIRED` / `CSRF_INVALID` / `ACCESS_DENIED`），并按 `Accept-Language` 本地化 `message`；前端 `isCsrfFailure` 优先读 `errorCode`，服务端未升级时退化为「受保护写请求 403」这一 HTTP 特征，**彻底移除中文文案匹配**。
7. **清理与修复**：删除无引用的 `EvaluationsPage.jsx`；`TopBar` 补 `/architecture` 面包屑映射；通知红点改为由真实任务状态驱动（60s 轮询且标签页隐藏时停止），不再常亮。

### 1.2 改动文件

**后端**
- `auth/SecurityConfiguration.java`：401/403 响应带 `errorCode`，用 `CsrfException` 区分 CSRF 失败与权限拒绝。
- `auth/AuthenticationFilter.java`：401 与管理员限制分支同样带 `errorCode` 并本地化。
- `resources/i18n/messages*.properties`：新增 `security.authRequired` / `security.csrfInvalid` / `security.accessDenied` / `security.adminOnly`（中英三份）。
- `test/java/dev/workbench/auth/SecurityErrorCodeIntegrationTest.java`（新增）。

**前端**
- 新增：`components/ErrorBoundary.jsx`、`components/VirtualList.jsx`、`hooks/useDocumentVisible.js`、`layout/RouteFallback.jsx`。
- 修改：`App.jsx`、`main.jsx`、`layout/WorkbenchShell.jsx`、`layout/TopBar.jsx`、`api/client.js`、`hooks/useRunExecution.js`、`features/workflows/RunFlowGraph.jsx`、`features/runs/RunDetailPage.jsx`、`features/runs/RunReplayPanel.jsx`、`features/runs/ToolApprovalDrawer.jsx`、`i18n/locales/{zh-CN,en-US}.json`。
- 删除：`features/evaluation/EvaluationsPage.jsx`（git 记录为 `D`）。
- 新增测试：`ErrorBoundary.test.jsx`、`VirtualList.test.jsx`；扩充 `useRunExecution.test.jsx`、`client.test.js`。

### 1.3 API / 迁移变更

- **不新增端点、不新增迁移。**
- 既有端点的错误响应体新增字段 `errorCode`（向后兼容：旧客户端忽略未知字段）。

### 1.4 实跑结果（真实命令输出）

| 门禁 | 命令 | 结果 |
| :--- | :--- | :--- |
| 后端全量 | `mvn test` | **721 用例 / 0 失败 / 0 错误 / 16 跳过**，BUILD SUCCESS |
| 架构守卫 | `ArchitectureGuardTest` | **5/5 通过** |
| 登录态 E2E | `AuthenticatedEndToEndWorkflowAndEvalTest` | **1/1 通过** |
| 前端格式 | `npm run format:check` | All matched files use Prettier code style |
| 前端静态检查 | `npm run lint` | 0 error / 0 warning |
| 前端单测 | `npm test` | **26 文件 / 99 用例全过** |
| 前端构建 | `npm run build` | 成功；`ArchitecturePage` 与 `ArchitectureCanvas` 已独立成 chunk |

> 基线对照：本轮开工前同一个 `mvn test` 为 **719 用例 / 0 失败 / 0 错误 / 16 跳过**；V0 新增 2 个后端用例、25 个前端用例。

### 1.5 手测指引

1. 打开任意非架构图页面，浏览器 DevTools → Network，确认没有加载 `ArchitecturePage` / `ArchitectureCanvas` chunk；点击左侧「架构图谱」后才出现。
2. 打开一个**已结束**的运行详情，DevTools → Network 过滤 `stream`，确认**没有** `/api/runs/{id}/stream` 请求，且没有 1.8s 周期请求。
3. 打开一个**运行中**的运行，确认出现 SSE 连接后周期请求停止；切到别的标签页再切回，确认轮询在隐藏期间未持续。
4. 「回放」页签滚动长事件列表，确认 DOM 节点数保持在数十量级（`data-testid="replay-event-list"` 的 `data-rendered`）。
5. 把界面切到 English，触发一次 CSRF 过期（可在另一标签页重新登录），确认自动重试成功而不是弹出中文报错。

---

## 二、V1：活的玻璃盒

### 2.1 设计要点

1. **真流式增量（`ControlledModel`）**：`OpenAIChatModel` 的 SSE 每个 chunk 只携带一个增量片段，`formatter.parseResponse` 逐帧产出 `ChatResponse`。原实现取「最后一个响应」当结果——一旦开流式，玻璃盒只会记录到最后几个字。本阶段引入 `StreamAccumulator`：
   - 正文（`TextBlock`）与思考链（`ThinkingBlock`）分桶累积，分别推送 `MODEL_DELTA` / `MODEL_REASONING_DELTA`；
   - `tool_use` 按 id 去重取最后一份（参数可能分帧到达）；
   - **兼容两类供应商**：只发增量（OpenAI 标准）与每帧发累积全文（部分兼容网关）——判据是「新片段以已累积内容开头且不更短」；
   - 累积器建在**内层** `Flux.defer`，与订阅同寿命：SDK 内部重试会重新订阅，建在外层会把上一轮正文再拼一遍；
   - 中转错误路径不变：仍释放费用预留、仍把 `failure` 信号传给后续调用；中途失败**不**产生终态快照。
2. **只在需要的地方开流式**：`UserModelConnectionFactory.model(connection, streaming)` 与 `DeepSeekModelFactory.deepSeekModel(streaming)` 默认 `false`；仅 `BuiltInModelProviders` 的 Agent 主循环传 `true`。规划、L2 摘要、评测答案等「只要完整结果」的调用保持整包返回，避免只支持整包响应的兼容网关失效（本轮实测中该设计直接避免了 2 个既有集成测试失效）。
3. **瞬态帧结构化**：`RunStreamTransient` 新增 `nodeId` 与 `Map detail`（保留 4 参构造以兼容旧调用），SSE 载荷变为 `{kind, text, nodeId, detail, occurredAt}`。
4. **逐关推送**：`ToolGateway` 把 15 处 `checks.add(...)` 收敛到单一 `addCheck(toolName, checks, check)`，在写入持久化轨迹的同时调用 `runtime.toolGateProgress(...)`；持久化的 `ToolCallTrace` 仍是唯一真相，瞬态帧只是把同一条事实提前推给正在观看的用户。
5. **思考链落库**：`ModelCallPayload` 新增 `reasoningContent` 与 `streamed`；`model_call_payloads` 表成对追加 `reasoning_content`（H2 `CLOB` / PG `TEXT`）与 `streamed`（`BOOLEAN`），长文本严格使用 `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`。
6. **前端 ReAct 泳道**：`buildReActTurns` 按服务端记录的时间顺序切轮——每次模型调用开启一个新 Turn，其后的工具调用归入该 Turn；历史数据里「先有工具、无模型记录」的尝试单独成轮而不是丢弃。思考链默认折叠且 `unmountOnExit`（长思考链常常上万字）。
7. **六道安检流水线**：`GatePipeline` 按后端**实际记录顺序**横向渲染关卡灯，缺省展开**被拦截的那一道**（`defaultGateIndex`），点击切换；`RISK_ASSESSMENT` 的状态值是风险等级（`LOW/MEDIUM/HIGH`）而非 `PASS/DENY`，配色单独处理。
8. **画布微状态**：`FlowNode` 叠加服务端 `focus.active` 给出的「此刻该节点在做什么」，以及最近一次增量帧的 `nodeId` 命中的流式指示——两者都是真实数据，不做本地推测。

### 2.2 改动文件

**后端**
- `framework/agent/execution/AgentRuntime.java`：新增 `modelReasoningDelta`、`toolGateProgress` 默认方法。
- `framework/event/RunStreamTransient.java`：新增 `nodeId` / `detail`。
- `framework/tool/ToolGateway.java`：`addCheck` 统一入口 + 逐关推送。
- `framework/trace/GlassboxModels.java`：`ModelCallPayload` 新增 `reasoningContent` / `streamed`。
- `infrastructure/agentscope/ControlledModel.java`：`StreamAccumulator`、双通道增量、`buildPayload` 改为按累积器生成。
- `infrastructure/model/{BuiltInModelProviders,UserModelConnectionFactory,DeepSeekModelFactory}.java`：`streaming` 参数化。
- `observability/RunStreamBroker.java`：瞬态载荷扩展。
- `orchestration/ControlledAgentRuntime.java`：`publishTransient` 统一推送；非 UUID 运行标识静默跳过。
- `persistence/entity/ModelCallPayloadEntity.java`、`store/GlassboxTraceStore.java`：思考链与流式标记的读写。
- `resources/db/migration/V15__model_call_reasoning.sql`、`db/migration-pg/V5__model_call_reasoning.sql`（**成对新增**）。
- 测试：`ControlledModelStreamingTest.java`（新增 4 例）、`FlywayMigrationCompatibilityTest.java`（期望版本 14→15，并补 `REASONING_CONTENT` / `STREAMED` 列断言）。

**前端**
- 新增：`features/runs/glassbox/GatePipeline.jsx`、`features/runs/glassbox/ReActSwimlane.jsx` 及两者单测。
- 修改：`features/runs/glassbox/ToolGatewayPanel.jsx`（改用横向流水线）、`features/runs/NodeGlassboxDialog.jsx`（新增「ReAct 实时泳道」分区）、`features/runs/RunDetailPage.jsx`（focus→画布、`streamNodeId`、深链白名单加 `react`）、`features/workflows/RunFlowGraph.jsx`（微状态）、`hooks/useRunExecution.js`（`streamNodeId` / `lastGateEvent`）、`i18n/locales/*.json`。

### 2.3 API / SSE / 迁移变更

**新增迁移（成对，仅新增列）**

| 库 | 文件 | 内容 |
| :--- | :--- | :--- |
| H2 | `V15__model_call_reasoning.sql` | `model_call_payloads` 追加 `reasoning_content CLOB`、`streamed BOOLEAN DEFAULT FALSE NOT NULL` |
| PG | `V5__model_call_reasoning.sql` | 同上，`reasoning_content TEXT`、`streamed BOOLEAN NOT NULL DEFAULT FALSE` |

**SSE 事件（均为瞬态，不落库）**

| 事件名 | 触发点 | 载荷 |
| :--- | :--- | :--- |
| `MODEL_DELTA` | `ControlledModel` 每收到正文片段 | `{kind, text, nodeId, detail:{}, occurredAt}` |
| `MODEL_REASONING_DELTA`（新增） | 同上，思考链片段 | 同上 |
| `TOOL_GATE_PROGRESS`（新增） | `ToolGateway.addCheck` 每产生一道判定 | `detail = {gate,label,status,detail}` |

> 鉴权与归属：沿用既有 `/api/runs/{runId}/stream`（`OwnershipService.requireRun` + 会话校验），**未新增端点**，因此没有引入新的鉴权面。

### 2.4 实跑结果

| 门禁 | 命令 | 结果 |
| :--- | :--- | :--- |
| 后端全量 | `mvn test` | **725 用例 / 0 失败 / 0 错误 / 16 跳过**，BUILD SUCCESS |
| 架构守卫 | `ArchitectureGuardTest` | **5/5 通过** |
| 登录态 E2E | `AuthenticatedEndToEndWorkflowAndEvalTest` | **1/1 通过** |
| 迁移兼容 | `FlywayMigrationCompatibilityTest` | **4/4 通过**（含 V15 新列断言） |
| 前端格式 / 静态检查 | `format:check` / `lint` | 全绿，0 error 0 warning |
| 前端单测 | `npm test` | **28 文件 / 112 用例全过** |
| 前端构建 | `npm run build` | 成功（1,212 modules；最大业务 chunk `RunDetailPage` 130.33 kB / gzip 40.05 kB） |

> 过程中出现并修复的 3 个回归（如实记录）：`McpApprovalLinkageTest`（非 UUID 运行标识导致瞬态推送抛错）、`ByokCompactionSummarizerTest` 与 `AgentBenchmarkIntegrationTest`（对只返回整包 JSON 的桩服务开启流式后解析为空）。修复方式是让瞬态推送容忍非 UUID 标识、并把流式限制在 Agent 主循环，而不是放宽断言。

### 2.5 手测指引

1. 配置一个真实 BYOK 连接（或本地兼容网关），发起一次「代码」任务并确认计划。
2. 进入运行详情：画布上正在执行的节点卡片底部出现 `流式输出中…` 蓝色指示与脉冲点（来自真实 `MODEL_DELTA` 的 `nodeId`）。
3. 点击该节点 → 宽屏玻璃盒 →「ReAct 实时泳道」：按 `T1 / T2 …` 展开，每轮先看到模型输出，思考链默认折叠（点「思考链（N 字）」展开），下方是该轮的工具调用。
4. 工具调用块顶部即六道安检横向流水线；发生拦截时**默认展开被拦截的那道门**并给出原因，点击任意关卡切换详情。
5. 运行结束后刷新页面：泳道内容与流水线应完全来自数据库（思考链来自 `model_call_payloads.reasoning_content`，关卡来自 `tool_call_traces`），不是前端缓存。

---

## 三、红线自检（本轮实测）

| # | 红线 | 自检结果 |
| :--- | :--- | :--- |
| 1 | 历史 Flyway 迁移零改动 | `git diff d70a9a4 --name-only -- <V1~V14 / PG V1~V4>` → **0 文件** |
| 2 | 新迁移成对追加 | H2 `V15` + PG `V5`，仅 `ADD COLUMN` |
| 3 | 长文本类型 | 新增列 `reasoning_content` 为 `CLOB`/`TEXT`，实体使用 `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`；全仓无 `@Lob String` |
| 4 | 敏感文件零追踪 | `git ls-files config/ data/` → **空** |
| 5 | 未推送远端 | 全程仅 `git commit`；`git status -sb` = `## master...origin/master [ahead 6]` |
| 6 | 架构守卫 | `ArchitectureGuardTest` **5/5**；Controller 未新增 JPA 依赖；未新增顶层包 |
| 7 | 前端无 `dangerouslySetInnerHTML` | 新增文件中 **0 处实际调用**（仅 `MarkdownView` 注释里声明禁止使用） |
| 8 | 中英键 1:1 | `zh-CN` **1603** / `en-US` **1603**，仅中文 0、仅英文 0 |
| 9 | 无跳过测试 | 测试代码中 `@Disabled` / `@Ignore` / `.skip(` / `xit(` → **0 处** |
| 10 | 未越界改动 | 未改动 `D:\software program\AI\agent-dev-workbench` 之外的任何文件；未触碰 `F:\tmp\demo-todo`；未引入新依赖（前端 `package.json` 未变） |

---

## 四、复现命令

```powershell
# 后端全量 + ArchUnit + 登录态 E2E
Set-Location 'D:\software program\AI\agent-dev-workbench'
$env:WORKBENCH_PG_TEST=""
mvn test

# 前端四步门禁
Set-Location 'D:\software program\AI\agent-dev-workbench\frontend\user-workbench'
npm run format:check; npm run lint; npm test; npm run build
```

---

## 五、未完成范围（V2–V6）与已勘察到的接入点

以下五项**尚未实施**。这里记录本轮勘察确认的真实接入点，供后续直接接棒，避免重复调研。

### V2 上下文压缩显微镜 + RAG / 三层记忆透视

- **已有数据（无需新表即可支撑显微镜）**：`framework/context/ContextBreakdown.java` 已包含 6 段分桶（`SYSTEM/SKILLS/MEMORY/HANDOFF/TOOL_OUTPUTS/HISTORY`）、`beforeTokens`、`totalEstimatedTokens`、`modelContextLimit`、`appliedTier`、`savedTokens/savedPercent`、`offloadedToolCallIds`（L1）、`summaryPreview`（L2）、`artifactRefs`（L1）、`retainedExcerptPreview`、`structuredAnchors`（L2）；该快照按模型调用持久化，并已通过节点详情 `attempts[].models[].payload.context` 暴露给前端。
- **仍需后端补齐**：L1 单条卸载的「原字符数 / 摘要字符数」、L2 合并的轮次范围、L3 保护的工具原子对清单；`context_artifact_read` 只读工具；`knowledge_search` 受控工具与「BM25/向量/RRF/最终入选/裁剪原因」的检索证据落库；工作区切片预览与三层记忆的查看/冻结接口。落点：`control/MultiTierContextCompactor.java`、`agent/BuiltInToolProviders.java`、`domain/ToolKind.java`、`framework/agent/assembly/AgentAssemblyPlanner.java`、`memory/knowledge/KnowledgeRagService.java`。
- **前端落点**：`features/runs/glassbox/ContextMeterBar.jsx`（升级为可展开显微镜）、`features/memory/`（当前为空目录）。

### V3 可视化编排画布

- **已有能力**：`planning/PlanCandidateValidator.java` 已产出写集互斥/环检测等结构化冲突；`PlanCandidateConfirmationService` 负责冻结执行。
- **需新增**：`POST /api/plans/validate-draft`（纯校验、严格鉴权与归属）；前端在 `features/tasks/PlanCandidateDialog.jsx` / `PlanAdjustmentEditor.jsx` 旁增加画布模式并复用 `@xyflow/react`。

### V4 时间轴火焰图 + Checkpoint 时间旅行 / What-If

- **已有数据**：`workflow_checkpoints` 快照、节点起止时间、模型/工具调用耗时、Gate 5.5 审批等待区间、L2 压缩事件。
- **需新增**：时间轴投影 API、Checkpoint 查询/对比 API、受控分叉重跑 API 与双 Run Diff API；前端 `features/checkpoints/`（当前为空目录）。

### V5 动态实时架构 X 光图

- **需新增**：`GET /api/runs/{runId}/architecture-xray`（`ownership.requireRun`）聚合五大引擎的激活次数/耗时/节省率/拦截数/跳转锚点；前端 `ArchitecturePage.jsx` 顶部模式选择器、步进回放滑块、模块指标胶囊，并校准 `architectureCatalog.js` / `architectureSubCatalog.js` 中 `Flyway V12` 等漂移文案（当前实际为 H2 `V1~V15` / PG `V1~V5`）。

### V6 OTel 导出 / 外部 Trace 接入 / 评测飞轮

- **需新增**：`GET /api/runs/{runId}/trace-export?format=otel|glassbox`（脱敏）、`POST /api/external-traces/runs` 与 `.../spans`（限流、限载荷、脱敏、归属当前用户）、Run→Eval 用例沉淀接口；前端 `RunHeader.jsx` 导出与沉淀入口、运行历史的来源区分与接入指南抽屉。

---

## 六、结论

V0 与 V1 已按规范实现、实跑验证并各自独立提交，工作区干净，全部红线自检通过。V2–V6 未实施，本文件不对其做任何完成性声明。
