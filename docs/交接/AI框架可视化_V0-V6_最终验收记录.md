# AI 框架可视化 · V0–V6 最终验收记录（2026-11-26 封板）

> 本记录只写实际交付与实跑数据。基线 `master @ d70a9a4`，本轮演进共 **13 个本地提交**（含 6 个 feat、2 个 fix、2 个 docs，以及上一轮 3 个），全程**未 push**。
> 累计改动：150 文件 / +13790 / −680。

## 1. V0–V6 里程碑总表

| 阶段 | 提交 | 主题 | 交付要点 |
| :--- | :--- | :--- | :--- |
| V0 | `cfa7109` | 可视化前端地基轻量化 + 运行态协同 | 前端分块与运行态协同、玻璃盒入口收敛 |
| V1 | `b300b8c` | 活的玻璃盒：真流式 + ReAct 实时泳道 + 六道安检流水线 | SSE 瞬态帧、`streamText/streamReasoning/lastGateEvent`、按节点分桶、`GatePipeline` |
| 补丁 | `dcd4372` | V0/V1 三处真实缺口 | `node-detail` 缓存失效、未消费流式字段接入 UI、累积型供应商净增量（`StreamAccumulator` 返回 delta） |
| V2 | `eee5246` | 上下文压缩显微镜 + Hybrid RAG / 三层记忆透视 | `ContextBreakdown.CompactionDetail`、L1/L2/L3 存证、`RagRetrievalXRay`、三层记忆面板、`KNOWLEDGE_SEARCH` / `CONTEXT_ARTIFACT_READ` 工具（显式授予） |
| V3 | `daab060` | 多 Agent 可视化编排校验画布 | `POST /plans/validate-draft`、写集冲突/成环/越界/未知依赖/重复键冲突分析 |
| V3 闭环 | `2b3b7c3` | 画布落库闭环 | `PUT /plan-candidates/{id}/draft`：服务端复核通过才写回，摘要更新后由既有确认创建运行 |
| V4 | `af8d0a8` | 执行时间轴 + Checkpoint 时间旅行 + 双跑对照 | 时间轴 Span 投影、检查点冻结状态、`POST /runs/{id}/forks` 分叉重跑、结构化 diff + 行级并排 diff |
| V5 | `23da2e8` | 动态实时架构 X 光 | `GET /runs/{id}/architecture-xray`、模块激活/耗时/压缩节省/拦截数、步进回放、一键回到证据 |
| 复核定稿 | `3948a94` | V4/V5 三处修补 | 分叉模型特权写反（安全）、整仓范围复验路径、X 光锚点按模块精准映射 + 最近运行下拉 |
| V6 | `9a0af00` | 开放式 Glass-Box Trace + 评测飞轮 | `trace-export`（OTel/Glass-Box）、外部 Trace 接入（归属/上限/脱敏/只读）、`eval-cases` 一键沉淀 |

## 2. 累计 API / SSE / 迁移总表

### 2.1 新增 HTTP 接口

| 方法 | 路径 | 鉴权 |
| :--- | :--- | :--- |
| POST | `/api/plans/validate-draft` | 可选工作区归属 |
| PUT | `/api/plan-candidates/{id}/draft` | 执行归属 |
| GET | `/api/context-artifacts/{ref}` | 运行归属 |
| GET | `/api/knowledge/{documentId}/chunks`、`/api/knowledge/xray` | 工作区归属 |
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

## 5. V6 端到端手测剧本

### 5.1 OTel / Glass-Box 导出

1. 打开任一已完成运行 → 顶部「开放出口」条 → 点「导出 OTel Trace」。
2. 浏览器应返回 `application/json`：`resourceSpans[0].resource.attributes` 含 `service.name=agent-workbench` 与 `workbench.run.id`。
3. `scopeSpans[0].spans` 条数 = 该运行时间轴里有起点的 Span 数；模型/工具 Span 的 `kind` 为 `3`，失败记录 `status.code=2`。
4. 同一运行再导出一次：`traceId` 与 `spanId` 完全一致（由 runId / spanId 稳定派生）。
5. 点「导出 Glass-Box JSON」→ `format=glassbox`，含 `summary` 与 `limitations`。

### 5.2 外部 Agent Trace 接入

1. 运行历史页 →「外部框架接入的 Trace」→ 点「接入外部 Agent 指南」→ 抽屉里能看到两个真实端点与限制项。
2. 按指南 `POST /api/external-traces/runs` 建一条运行 → 列表出现该卡片（`0 个 Span`）。
3. `POST .../spans` 推 3 条（NODE/MODEL/TOOL）→ 卡片变 `3 个 Span`。
4. 点卡片 → 只读透视：甘特 + Span 明细 + 「只读透视」标签 + 边界说明；没有任何执行/恢复/分叉按钮。
5. 用另一个账号访问同一 id → `404`；把 `kind` 改成 `SECRETS` → `400`；时间戳写 `yesterday` → `400 ISO-8601`。
6. 属性里塞 `Bearer sk-...` → 读回时已被脱敏，不会原样出现。

### 5.3 评测飞轮

1. 任一已完成运行 → 点「沉淀为评测用例」→ 弹窗展示脱敏预览（提示词 + `RUN_STATUS_MATCH` + `OUTPUT_CONTAINS` 期望值 + 限制说明）。
2. 点「确认沉淀」→ 成功提示出现，「去评测实验室」按钮可跳转。
3. 评测实验室 → 自定义数据集 `run-distilled` 下出现样本 `run-<runId 前 12 位>`。
4. 打开该样本做一次回归：评分规则与既有自定义数据集完全一致（未新增断言类型）。
5. 同一条运行再沉淀一次 → 覆盖同一样本键，不产生重复用例。

### 5.4 回归确认（退出标准）

按 §6.1–6.3 复跑 V3 闭环 / V4 时间轴与分叉 / V5 X 光三条剧本，全部行为不变。

## 6. 已知边界（封板时仍存在，均已在代码注释与文档中标注）

1. **V3 节点级异构绑定未实现**：`PlanCandidateDraft.Node` 没有 model / skill / MCP 字段，画布只能编排角色、目标、依赖、读写范围、产出、验收与反思回边。
2. **V2 新工具不默认授予内置角色**：`KNOWLEDGE_SEARCH`、`CONTEXT_ARTIFACT_READ` 只在自定义 Agent 显式声明时出现（最小权限；内置角色指纹不因此变化）。
3. **V4 分叉保真度**：复制节点定义引用、依赖、读写范围、产出与验收条件；**不复制**节点级固定模型策略与上游交接载荷（协议缺字段），统一沿用任务模型；分叉继续写同一个工作区（已在弹窗与文档明示）。
4. **双跑对照的文件口径**：历史不保存交付文件逐版本内容，因此 `diff` 对照「声明写入范围 + 节点产出」，不做代码逐行 diff（响应里显式写明该限制）。
5. **外部 Trace 只读**：没有计划、工具授权与检查点，因此不参与执行/恢复/分叉/评测沉淀；工作台画布与六道安检对它是不可用的，界面只提供时间线与 Span 明细。
6. **OTel 导出丢无法定时的记录**：缺开始时间的 Span 不进 OTel（规范要求起止时间），改记入 `_limitations`。

## 7. 相关文档

- [AI 框架可视化 V3 闭环 + V4 + V5 阶段验收记录](AI框架可视化_V3闭环-V5_阶段验收记录_2026-11-26.md)
- [AI 框架可视化 V0–V3 阶段验收记录](AI框架可视化_V0-V3_阶段验收记录.md)
- [AI 框架可视化 V0–V1 阶段验收记录](AI框架可视化_V0-V1_阶段验收记录.md)
- [外部 Agent Trace 接入指南](外部Agent_Trace接入指南_2026-11-26.md)
