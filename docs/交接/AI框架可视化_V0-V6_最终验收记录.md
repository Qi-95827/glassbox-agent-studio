# AI 框架可视化 · V0–V6 最终验收记录（2026-11-26 封板）

> 本记录只写实际交付与实跑数据。基线 `master @ d70a9a4`，本轮演进共完成 **V0–V6 全量交付与终审修补**（截止 `cb39a4c`，详见下表），全程本地提交、**未 push**。
> 原 V0–V1、V0–V3、V3 闭环–V5 三份中间阶段验收文档及演进交接提示已合并归口至本文档并清理，本文档为 **V0–V6 唯一权威验收记录**。

## 1. V0–V6 里程碑总表

| 阶段 | 提交 | 主题 | 交付要点 |
| :--- | :--- | :--- | :--- |
| V0 | `cfa7109` | 可视化前端地基轻量化 + 运行态协同 | 10 路由页 `React.lazy` 懒加载、三级 `ErrorBoundary`、SSE/轮询协同与 280ms 防抖、`FlowNode` `React.memo`、自研 `VirtualList` 虚拟滚动、结构化 `errorCode`（`AUTH_REQUIRED`/`CSRF_INVALID`/`ACCESS_DENIED`）、删除死文件 `EvaluationsPage.jsx` |
| V1 | `b300b8c` | 活的玻璃盒：真流式 + ReAct 实时泳道 + 六道安检流水线 | `ControlledModel` `StreamAccumulator`（主循环流式 + 思考链分道落库 H2 `V15`/PG `V5`）、SSE 瞬态帧 `MODEL_DELTA`/`MODEL_REASONING_DELTA`/`TOOL_GATE_PROGRESS`、`ReActSwimlane`、`GatePipeline` |
| 补丁 | `dcd4372` | V0/V1 三处真实缺口 | `flushInvalidate` 补齐 `node-detail` 缓存失效、`streamsByNode` 按节点分桶并接入 UI 实时打字机展示、累积型供应商净增量（`StreamAccumulator` 返回 `StreamDeltas`） |
| V2 | `eee5246` | 上下文压缩显微镜 + Hybrid RAG / 三层记忆透视 | `ContextBreakdown.CompactionDetail`、纯 SVG 六层堆叠面积图 + 阈值线 + `artifactRef` 原文弹窗、`RagRetrievalXRayPanel`（BM25/向量 → RRF 漏斗）、`MemoryAndChunksPanel`、`knowledge_search` / `context_artifact_read` 受控工具（显式授予） |
| V3 | `daab060` | 多 Agent 可视化编排校验画布 | `POST /api/plans/validate-draft`、写集冲突/成环/越界/未知依赖/重复键冲突分析、`PlanComposerCanvas`（`@xyflow/react` + Dagre） |
| V3 闭环 | `2b3b7c3` | 画布落库闭环 | `PUT /api/plan-candidates/{id}/draft`：条件更新 + 乐观锁摘要校验 + `PlanCandidateValidator` 复核通过才写回，确认即按修改图运行 |
| V4 | `af8d0a8` | 执行时间轴 + Checkpoint 时间旅行 + 双跑对照 | 7 类 Span 时间轴火焰图、Checkpoint 冻结状态检视、`POST /api/runs/{id}/forks` 分叉重跑（H2 `V16`/PG `V6` `run_forks`）、双跑结构化对照 + 行级并排 Diff |
| V5 | `23da2e8` | 动态实时架构 X 光 | `GET /api/runs/{id}/architecture-xray`、架构页「静态教学 ↔ 实时 X 光」模式切换、模块激活/耗时/压缩节省/拦截数、步进回放与一键跳回证据 |
| 复核定稿 | `3948a94` | V4/V5 三处修补 | 修正分叉 `DEEPSEEK` vs `USER` 特权校验写反、整仓（`>20` 文件）分叉改走目录复验、X 光锚点按模块精准映射 Tab + 最近运行下拉 |
| V6 | `9a0af00` | 开放式 Glass-Box Trace + 评测飞轮 | `trace-export`（OTel/Glass-Box 脱敏导出）、外部 Trace 接入（H2 `V17`/PG `V7`，归属/上限/脱敏/只读透视）、`eval-cases` 一键沉淀 |
| 终审修补 | `cb39a4c` | V6 评测飞轮两处实锤修补 | `EvalFlywheelService` 默认类别修正为有效枚举 `CODING_REFLECTION`（修复首次点击报 400）；`GET /eval-case-preview` 改为严格只读零写库 |

## 2. 累计 API / SSE / 迁移总表

### 2.1 新增 HTTP 接口

| 方法 | 路径 | 鉴权 |
| :--- | :--- | :--- |
| POST | `/api/plans/validate-draft` | 可选工作区归属 |
| PUT | `/api/plan-candidates/{id}/draft` | 执行归属 |
| GET | `/api/context-artifacts/{ref}` | 运行归属 |
| GET | `/api/workspaces/{workspaceId}/knowledge/{documentId}/chunks`、`/api/workspaces/{workspaceId}/knowledge/xray` | 工作区归属 |
| GET | `/api/runs/{id}/timeline` | `ownership.requireRun` |
| GET | `/api/runs/{id}/checkpoints` | `ownership.requireRun` |
| GET | `/api/runs/{id}/diff/{otherId}` | 两侧 `requireRun` |
| POST | `/api/runs/{id}/forks` | `ownership.requireRun` |
| GET | `/api/runs/{id}/forks` | `ownership.requireRun` |
| GET | `/api/runs/{id}/architecture-xray` | `ownership.requireRun` |
| GET | `/api/runs/{id}/trace-export?format=otel\|glassbox` | `ownership.requireRun` |
| GET/POST | `/api/external-traces/runs` | 当前账号归属（服务端推导） |
| GET/POST | `/api/external-traces/runs/{id}`、`/spans` | 当前账号归属；跨账号 404 |
| GET | `/api/runs/{id}/eval-case-preview` | `ownership.requireRun` |
| POST | `/api/runs/{id}/eval-cases` | `ownership.requireRun` |

### 2.2 SSE 瞬态帧（不落库）

`MODEL_DELTA`、`MODEL_REASONING_DELTA`、`TOOL_GATE_PROGRESS`。持久真相仍是 `model_call_payloads` + `tool_call_traces`。

### 2.3 迁移

| 版本 | 文件 | 内容 |
| :--- | :--- | :--- |
| H2 `V15` / PG `V5` | `V15__model_call_reasoning.sql` / `V5__model_call_reasoning.sql` | `model_call_payloads` + `reasoning_content`、`streamed` |
| H2 `V16` / PG `V6` | `V16__run_forks.sql` / `V6__run_forks.sql` | 新表 `run_forks` |
| H2 `V17` / PG `V7` | `V17__external_traces.sql` / `V7__external_traces.sql` | 新表 `external_trace_runs`、`external_trace_spans` |

`V1~V14`（H2）与 `V1~V4`（PG）**零改动**；三次新增均为成对追加，且未使用 `@Lob String`。

## 3. 全量门禁实跑数据（封板轮）

后端 `mvn test`（全量，含架构与迁移守卫）：

```
Tests run: 778, Failures: 0, Errors: 0, Skipped: 16
dev.workbench.architecture.ArchitectureGuardTest            Tests run: 5, Failures: 0
dev.workbench.persistence.FlywayMigrationCompatibilityTest  Tests run: 4, Failures: 0
dev.workbench.AuthenticatedEndToEndWorkflowAndEvalTest      通过（包含在 778 内）
BUILD SUCCESS
```

前端四步门禁：

```
npm run format:check  -> All matched files use Prettier code style!
npm run lint          -> 0 problems
npm test              -> Test Files 37 passed (37) / Tests 169 passed (169)
npm run build         -> ✓ built
```

i18n：`zh-CN` **1800** / `en-US` **1800**，键差 0。

新增用例（本轮累计）：`ControlledModelStreamingTest` 6、`ContextArtifactServiceTest` 6、`KnowledgeSearchToolTest` 5、`RoleToolGrantTest` 5、`PlanDraftConflictAnalyzerTest` 7、`PlanCandidateIntegrationTest` 9、`RunForkServiceTest` 7、`RunObservationServiceTest` 4、`ArchitectureXrayServiceTest` 3、`TraceExportServiceTest` 4、`ExternalTraceServiceTest` 5、`EvalFlywheelServiceTest` 4；前端 `ContextMicroscopePanel` 6、`RagRetrievalXRayPanel` 5、`MemoryAndChunksPanel` 4、`PlanComposerCanvas` 10、`ExecutionTimelinePanel` 4、`RunCompareDiffView` 7、`ArchitectureXrayPanel` 7、`RunTraceActions` 3、`ExternalTraceSection` 3，以及 `useRunExecution` / `ReActSwimlane` / `GatePipeline` / `client` 扩充。

## 4. 十条红线自检

| # | 红线 | 结论 | 证据 |
| :-- | :--- | :--- | :-- |
| 1 | 历史迁移零改动 | ✅ | `git diff d70a9a4..HEAD` 对 H2 `V1~V14`、PG `V1~V4` 为 0 行 |
| 2 | 新迁移成对追加 | ✅ | `V15/V5`、`V16/V6`、`V17/V7` 三对，语义一致 |
| 3 | 禁 `@Lob String` | ✅ | 全仓 `@Lob` 计数 0；新字段用 `@JdbcTypeCode(SqlTypes.LONGVARCHAR)` |
| 4 | 敏感文件零追踪 | ✅ | 无 `config/`、`data/` 入库；接入指南不含任何真实密钥 |
| 5 | 中英 1:1 | ✅ | 1800 / 1800，键差 0 |
| 6 | 零 `dangerouslySetInnerHTML` | ✅ | 唯一命中是 `MarkdownView` 中「严禁使用」的注释 |
| 7 | 零跳过测试 | ✅ | `@Disabled`/`@Ignore`/`.skip(`/`xit(` 计数 0（16 个 skipped 是历史 `@EnabledIf` 环境守卫） |
| 8 | 零假数据 | ✅ | 所有指标来自落库记录；缺时间/缺载荷显式 `null` + `limitations` |
| 9 | 每阶段独立本地提交 | ✅ | 6 feat + 2 fix + 2 docs，阶段边界清晰 |
| 10 | 严禁 `git push` | ✅ | `## master...origin/master [ahead 17]`，仅本地提交 |

## 5. V0–V6 端到端手测剧本

> 本地启动命令（仓库根目录 `D:\software program\AI\agent-dev-workbench`）：`mvn spring-boot:run`，访问 `http://127.0.0.1:8088`。

### 5.1 V0–V2（地基协同、实时流式泳道、显微镜与 RAG/记忆透视）

1. **V0 懒加载与 SSE**：打开首页，DevTools Network 确认无 `ArchitecturePage` chunk，切到「架构图谱」才加载；打开已结束运行无 `/stream` 连接与 1.8s 轮询；「回放」长列表 DOM 行数维持数十级；切 English 后触发 CSRF 过期可自动重试。
2. **V1 实时泳道与安检流水线**：用真实 BYOK 发起代码任务 → 画布运行节点显示 `流式输出中…` → 玻璃盒「ReAct 实时泳道」底部实时展示思考链（可折叠）、正文流与当前过检关卡；跑完刷新仍从数据库完整还原；工具块顶部六道安检灯在拦截时默认展开被拒关卡。
3. **V2 上下文显微镜与 RAG/记忆**：玻璃盒「模型 I/O」→ 展开「上下文显微镜」查看六层堆叠面积图 + L1(60%)/L2(78%)/L3(92%) 阈值线 + 压缩对照，点 `artifactRef` 弹窗读原文；工作区「知识库」检索看 BM25/向量 → RRF 漏斗与裁剪原因；工作区「记忆与切片」管理三层记忆与查看文档切片边界。

### 5.2 V3–V5（编排画布落库、时间旅行与实时架构 X 光）

1. **V3 画布编排闭环**：计划确认弹窗切「可视化编排」→ 填两节点写入同一文件或连成环 → 冲突节点变红且「应用画布到计划」禁用；修正为合法图并修改标题/验收 → 应用 → 确认创建运行，详情页直接生效。
2. **V4 时间轴与分叉对照**：打开已结束运行 →「执行时间轴」看 7 类 Span 泳道，点色块跳对应节点玻璃盒；「Checkpoint」补充一句话发起分叉（运行中运行会被 400 拒绝）→ 分叉完成后回主线「分支对照」看双轨指标与逐节点并排 diff。
3. **V5 动态架构 X 光**：进入「架构图谱」切「实时 X 光」→ 从「最近运行」下拉选择（或粘贴 Run ID）→ 仅真实触发的模块显示 `ACTIVE` 与耗时/压缩率/拦截数；拖动步进滑块观察路径；点任意激活模块锚点精准跳到运行详情对应玻璃盒 Tab。

### 5.3 V6：OTel / Glass-Box 导出

1. 打开任一已完成运行 → 顶部「开放出口」条 → 点「导出 OTel Trace」。
2. 浏览器应返回 `application/json`：`resourceSpans[0].resource.attributes` 含 `service.name=agent-workbench` 与 `workbench.run.id`。
3. `scopeSpans[0].spans` 条数 = 该运行时间轴里有起点的 Span 数；模型/工具 Span 的 `kind` 为 `3`，失败记录 `status.code=2`。
4. 同一运行再导出一次：`traceId` 与 `spanId` 完全一致（由 runId / spanId 稳定派生）。
5. 点「导出 Glass-Box JSON」→ `format=glassbox`，含 `summary` 与 `limitations`。

### 5.4 V6：外部 Agent Trace 接入

1. 运行历史页 →「外部框架接入的 Trace」→ 点「接入外部 Agent 指南」→ 抽屉里能看到两个真实端点与限制项。
2. 按指南 `POST /api/external-traces/runs` 建一条运行 → 列表出现该卡片（`0 个 Span`）。
3. `POST .../spans` 推 3 条（NODE/MODEL/TOOL）→ 卡片变 `3 个 Span`。
4. 点卡片 → 只读透视：甘特 + Span 明细 + 「只读透视」标签 + 边界说明；没有任何执行/恢复/分叉按钮。
5. 用另一个账号访问同一 id → `404`；把 `kind` 改成 `SECRETS` → `400`；时间戳写 `yesterday` → `400 ISO-8601`。
6. 属性里塞 `Bearer sk-...` → 读回时已被脱敏，不会原样出现。

### 5.5 V6：评测飞轮

1. 任一已完成运行 → 点「沉淀为评测用例」→ 弹窗展示脱敏预览（严格只读不写库；含提示词 + `RUN_STATUS_MATCH` + `OUTPUT_CONTAINS` 期望值 + 限制说明）。
2. 点「确认沉淀」→ 按需创建 `CODING_REFLECTION` 类别的 `run-distilled` 数据集并写入样本 → 成功提示出现，「去评测实验室」按钮可跳转。
3. 评测实验室 → 自定义数据集 `run-distilled` 下出现样本 `run-<runId 前 12 位>`。
4. 打开该样本做一次回归：评分规则与既有自定义数据集完全一致（未新增断言类型）。
5. 同一条运行再沉淀一次 → 覆盖同一样本键，不产生重复用例。

## 6. 已知边界（封板时仍存在，均已在代码注释与文档中标注）

1. **V3 节点级异构绑定未实现**：`PlanCandidateDraft.Node` 没有 model / skill / MCP 字段，画布只能编排角色、目标、依赖、读写范围、产出、验收与反思回边。
2. **V2 新工具不默认授予内置角色**：`KNOWLEDGE_SEARCH`、`CONTEXT_ARTIFACT_READ` 只在自定义 Agent 显式声明时出现（最小权限；内置角色指纹不因此变化）。
3. **V4 分叉保真度**：复制节点定义引用、依赖、读写范围、产出与验收条件；**不复制**节点级固定模型策略与上游交接载荷（协议缺字段），统一沿用任务模型；分叉继续写同一个工作区（已在弹窗与文档明示）。
4. **双跑对照的文件口径**：历史不保存交付文件逐版本内容，因此 `diff` 对照「声明写入范围 + 节点产出」，不做代码逐行 diff（响应里显式写明该限制）。
5. **外部 Trace 只读**：没有计划、工具授权与检查点，因此不参与执行/恢复/分叉/评测沉淀；工作台画布与六道安检对它是不可用的，界面只提供时间线与 Span 明细。
6. **OTel 导出丢无法定时的记录**：缺开始时间的 Span 不进 OTel（规范要求起止时间），改记入 `_limitations`。

## 7. 相关文档

- [外部 Agent Trace 接入指南](外部Agent_Trace接入指南_2026-11-26.md)
- [项目总览：产品需求与技术架构](../项目总览_产品需求与技术架构.md)
- [Top 10 安全止血与 P0 修复验收记录](Top10安全止血与P0修复_验收记录.md)
