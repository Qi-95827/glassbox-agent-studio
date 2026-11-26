# AI 框架可视化演进 · V0–V3 阶段验收记录

> **记录日期**：2026-11-26
> **代码仓库**：`D:\software program\AI\agent-dev-workbench`
> **基线**：`d70a9a4`（本轮开工前）
> **本轮新增提交**：`dcd4372`（V0/V1 补丁）→ `eee5246`（V2）→ `daab060`（V3）
> **当前状态**：`master` 本地领先 `origin/master` 11 个提交，**全部未推送**

---

## 〇、状态声明（请先读）

| 阶段 | 状态 | 提交 |
| :--- | :--- | :--- |
| V0 前端地基轻量化 | ✅ 已完成并实跑验证 | `cfa7109` |
| V1 活的玻璃盒（真流式 + 泳道 + 安检流水线） | ✅ 已完成并实跑验证 | `b300b8c` |
| **V0/V1 三项缺口补丁** | ✅ 已修复并实跑验证 | `dcd4372` |
| **V2 上下文压缩显微镜 + RAG/记忆透视** | ✅ 已完成并实跑验证（含下述两项边界） | `eee5246` |
| **V3 可视化编排校验画布** | ⚠️ 已完成「编排 + 实时校验」部分，**保存/冻结链路未接入**（见 §4.4） | `daab060` |
| V4~V6 | ❌ 未实施 | — |

**没有占位实现、假数据或空壳接口。** 本文件与代码中都不存在「声称已完成但实际未实现」的能力；第 §5 节数字全部来自本轮真实命令输出。

---

## 一、V0/V1 补丁（`dcd4372`）

### 1.1 修复内容

1. **SSE 期间 node-detail 不刷新（V0 Bug）**：`useRunExecution.js` 的失效集合补上 `['node-detail', runId]`。此前 `streamConnected === true` 会关掉 `useNodeDetail` 轮询，而 SSE 又不失效该查询，导致正打开的节点抽屉/玻璃盒停在打开那一刻的快照。
2. **实时增量没有消费者（V1 缺口）**：
   - 增量改为**按 `nodeId` 分桶**（`streamsByNode`）：并行节点交错推送时共用一个缓冲会把两个节点的正文拼在一起；
   - 该节点 `MODEL_STARTED` 时清空该桶；运行进入终态（`RUN_COMPLETED`/`FAILED`/`CANCELLED`/`LIMITED`/`INTERRUPTED`/`BLOCKED`）整体清空；切换 `runId` 重置；
   - 链路 `RunDetailPage → NodeInspector → NodeGlassboxDialog → ReActSwimlane`，泳道底部新增实时块：实时思考链（可折叠）、正文打字机（带光标）、「正在过检：<关卡> · <状态>」。
3. **累积全文型供应商的净增量（V1 缺陷）**：`StreamAccumulator.accept` 改为返回 `StreamDeltas(text, reasoning)`，`emitDeltas` 只推净增量。此前累积型网关每帧返回全文，前端会重复拼接；现在重复帧产生空增量。

### 1.2 新增测试

- 后端：`ControlledModelStreamingTest` 增 3 例（累积型净增量、累积型思考链净增量、重复帧无增量）。
- 前端：`useRunExecution.test.jsx` 增 5 例（node-detail 失效、按节点分桶、换轮清空、关卡分桶、终态清空）；`ReActSwimlane.test.jsx` 增 3 例（实时正文/思考链/关卡、等待提示、无增量不渲染）。

### 1.3 实跑

| 门禁 | 结果 |
| :--- | :--- |
| `mvn test` | **727 用例 / 0 失败 / 0 错误 / 16 跳过** |
| `ArchitectureGuardTest` | 5/5 |
| `npm test` | 28 文件 / 120 用例 |

---

## 二、V2 上下文压缩显微镜 + Hybrid RAG / 三层记忆透视（`eee5246`）

### 2.1 后端

1. **结构化压缩明细（不新增表/列）**：`ContextBreakdown` 新增 `CompactionDetail`
   - `offloads`：L1 逐条卸载（`toolCallId`、`artifactRef`、原文与保留字符数）；
   - `mergedFrom/mergedTo/mergedMessageCount`：L2 摘要合并的原始消息区间与条数（在摘要发生前留底，摘要后无法回推）；
   - `protectedToolCallIds`：L3 之后仍成对保留的工具调用。
   明细随既有 `context_breakdown_json` 落库读取；旧快照缺字段时按空值处理。
2. **`context_artifact_read` 只读工具**：`ContextArtifactService.readForRun` 采用**运行内匹配**（产物 `runId` 必须等于调用运行），比 `ownerScope` 更窄，且异步线程无需 HTTP 会话；正文按 24k 字符截断并显式回传 `truncated`。为把运行标识传到工具层，`AgentToolProvider.Scope` 与 `AgentToolContext` 新增 `runId`（保留全部旧构造）。
3. **`knowledge_search` 只读工具 + 透明召回证据**：`KnowledgeRagService.retrieveWithEvidence` 与既有 `retrieve` 共用同一套阈值、排序与截断，额外返回每个候选的 `bm25 / vector / rrf / score / selected / droppedReason`（`BELOW_THRESHOLD`、`OVER_LIMIT`）。工具对模型只暴露命中计数摘要（`summarize`），证据留在工具轨迹里供透视面板读取，不进入模型上下文。
4. **只读 API**：`GET /api/workspaces/{id}/knowledge/{documentId}/chunks`（切片边界与预览）、`GET /api/workspaces/{id}/knowledge/xray`（召回证据）。两者都在控制器入口统一 `ownership.requireWorkspace`。三层记忆的查看与人工放行/冻结复用既有 `/memories`（`accept`/`ignore`/`update`/`delete`，带版本号防并发覆盖）。

### 2.2 前端

1. **上下文显微镜**：`ContextMeterBar` 可展开 `ContextMicroscopePanel`
   - 纯 SVG **六层堆叠面积图**（SYSTEM/SKILLS/MEMORY/HANDOFF/TOOL_OUTPUTS/HISTORY）+ **L1 60% / L2 78% / L3 92%** 阈值虚线 + 调用点可点击；
   - **压缩前后对照**：L1 逐条（原文→保留字符数、`artifactRef` 可点击）、L2 合并区间与摘要预览、L3 受保护工具调用名单；
   - 点击 `artifactRef` 通过受鉴权接口拉取**真实原文**弹窗展示（含原始字符数与截断提示）。
2. **`features/memory/`**：
   - `RagRetrievalXRayPanel`：BM25 词法 Top-K / 向量语义 Top-K 双列打分条 → RRF 融合列表，入选/裁剪徽章与裁剪原因；
   - `MemoryAndChunksPanel`：三层记忆分组（来源运行、命中次数、状态、放行/冻结）+ 文档切片边界预览（序号、章节、Token、字符数、预览截断标记）；
   - 挂载：工作区新增「记忆与切片」页签；知识库检索后展示召回透视。

### 2.3 已知边界（必须知悉）

- **两个新工具已完整接入类型/契约/提供者/装配/网关审计链路，但内置角色的默认工具集没有扩充**：把 `KNOWLEDGE_SEARCH` / `CONTEXT_ARTIFACT_READ` 加进 `AgentRoleRegistry` 内置角色会改变既有 Agent 的定义指纹与注册工具数，实测导致 `WorkbenchIntegrationTest` 两个既有用例由 `BLOCKED` 变 `FAILED`（run 侧工具预算/装配约束）。为避免**静默扩大既有 Agent 的权限面**，本轮保持内置角色原状；自定义 `AgentDefinition`（其 permission profile 声明了这两个 kind）可正常装配 —— 装配链路与授权交集规则已由 `RoleToolGrantTest` 覆盖，默认授予留待专门迭代。

---

## 三、V3 可视化编排校验画布（`daab060`）

### 3.1 后端

1. **`PlanDraftConflictAnalyzer`（纯函数、无 IO）**：输出结构化冲突
   - `WRITE_CONFLICT`：两节点写集相交，带回两个节点键与**重叠文件路径**（大小写与分隔符归一）；
   - `CYCLE`：沿 `dependsOn` 深搜，带回**环上的节点链**；
   - `INVALID_SCOPE`：绝对路径、盘符、`..`、空路径；
   - `UNKNOWN_DEPENDENCY`、`DUPLICATE_KEY`；
   - 同时返回 `byNodeKey` 索引，前端可直接高亮对应节点。
2. **`POST /api/plans/validate-draft`**：只校验、不落库、不创建候选；请求体 `{draft, input?}`，带 `input` 时额外运行真实的 `PlanCandidateValidator` 并把业务错误一并返回；`input.workspaceId` 存在时先 `ownership.requireWorkspace`。

### 3.2 前端

`PlanComposerCanvas`（`@xyflow/react` + Dagre，与运行详情同一套布局参数）：
- 增删节点、切换角色（ANALYZE/IMPLEMENT/REVIEW）、编辑读写集合、调整依赖；
- 实线依赖边 + **虚线反思回边**，点击回边可调 `maxRevisions`（1~5）；
- 结构调整后 **350ms 防抖**调用校验接口，冲突节点**红色脉冲** + 冲突码 + 重叠文件名徽章，冲突列表带环上节点键；
- 计划确认弹窗新增「流程图 / 可视化编排」切换。

### 3.3 已知边界（必须知悉）

- **画布是「本地演练」**：可以改图并实时看合法性，但**不会**把改动写回服务端计划，也不替代既有的计划确认与冻结流程（画布顶部有明确提示）。真正的保存需要把 draft 转成 `plan-proposal` 并由服务端校验后 apply，这条写链路本轮未实现。
- **节点级异构模型 / 私有 Skill / MCP 工具绑定未实现**：`PlanCandidateDraft.Node` 当前只有 `role / objective / dependsOn / readFiles / writeFiles / expectedOutputs / acceptanceCriteria / agentRef`，没有模型、技能、MCP 字段；要支持需扩展协议并同步 `PlanCandidateValidator`、物化与装配，属于独立迭代。

---

## 四、新增 API / SSE / 迁移清单

### 4.1 新增 HTTP 端点

| 方法 | 路径 | 鉴权与归属 | 用途 |
| :--- | :--- | :--- | :--- |
| POST | `/api/plans/validate-draft` | 全局会话校验；带 `input.workspaceId` 时 `ownership.requireWorkspace` | 编排画布实时结构校验（只校验不落库） |
| GET | `/api/workspaces/{id}/knowledge/{documentId}/chunks` | `ownership.requireWorkspace` | 切片边界与预览 |
| GET | `/api/workspaces/{id}/knowledge/xray` | `ownership.requireWorkspace` | BM25/向量/RRF 召回证据 |

### 4.2 新增只读工具（受 ToolGateway 审计）

| 工具名 | ToolKind | 归属校验 | 说明 |
| :--- | :--- | :--- | :--- |
| `knowledge_search` | `KNOWLEDGE_SEARCH` | 工作区隔离（`requireWorkspace` 在 HTTP 层，工具层限定 workspaceId） | 返回命中摘要；证据随工具轨迹落库 |
| `context_artifact_read` | `CONTEXT_ARTIFACT_READ` | **运行内匹配**（`artifact.runId == 当前 runId`） | 正文 24k 字符截断并显式标注 |

### 4.3 SSE 事件（沿用 V1，无新增）

`MODEL_DELTA`、`MODEL_REASONING_DELTA`、`TOOL_GATE_PROGRESS`（均为瞬态、不落库）。

### 4.4 Flyway

**本轮 V2/V3 未新增任何迁移**。V1 引入的成对迁移保持：H2 `V15__model_call_reasoning.sql` / PG `V5__model_call_reasoning.sql`（仅新增 `reasoning_content`、`streamed` 两列）。历史迁移 `V1~V14`、PG `V1~V4` **零改动**。

---

## 五、全量门禁实跑结果

| 阶段 | `mvn test` | ArchUnit | E2E | Flyway | 前端单测 | 构建 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| V0 | 721 / 0 / 0 / 16 | 5/5 | 1/1 | 4/4 | 26 文件 / 99 用例 | ✅ |
| V1 | 725 / 0 / 0 / 16 | 5/5 | 1/1 | 4/4 | 28 文件 / 112 用例 | ✅ |
| 补丁 | 727 / 0 / 0 / 16 | 5/5 | 1/1 | 4/4 | 28 文件 / 120 用例 | ✅ |
| V2 | **743 / 0 / 0 / 16** | 5/5 | 1/1 | 4/4 | **31 文件 / 135 用例** | ✅ |
| V3 | **750 / 0 / 0 / 16** | 5/5 | 1/1 | 4/4 | **32 文件 / 141 用例** | ✅ |

格式 `format:check` 与静态检查 `lint` 在每阶段均为 0 error / 0 warning。

---

## 六、红线自检

| # | 红线 | 结果 |
| :--- | :--- | :--- |
| 1 | 历史迁移零改动 | `git diff d70a9a4 -- <V1~V14 / PG V1~V4>` → **0 文件** |
| 2 | 新迁移成对追加 | H2 `V15` + PG `V5`（V1 引入，V2/V3 无新增） |
| 3 | 长文本类型 | `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`；全仓 `@Lob` **0 处** |
| 4 | 敏感文件零追踪 | `git ls-files config/ data/` → **空** |
| 5 | 未推送远端 | `## master...origin/master [ahead 11]`，工作区干净 |
| 6 | 架构铁律 | ArchUnit 5/5；未新增顶层包；Controller 不依赖 JPA Repository |
| 7 | 无 `dangerouslySetInnerHTML` | 全前端**实际使用 0 处** |
| 8 | 中英 1:1 | `zh-CN` **1702** / `en-US` **1702**，仅中 0、仅英 0 |
| 9 | 无跳过测试 | `@Disabled`/`@Ignore`/`.skip(`/`xit(` → **0 处** |
| 10 | 未越界 | 未修改仓库外文件、未触碰 `F:\tmp\demo-todo`、未新增前端依赖 |

---

## 七、手测剧本（V0~V3）

```powershell
Set-Location 'D:\software program\AI\agent-dev-workbench'
mvn spring-boot:run        # http://127.0.0.1:8088
```

1. **V0**：打开首页，DevTools Network 无 `ArchitecturePage` chunk；打开**已结束**运行，无 `/stream` 请求；「回放」长列表 DOM 行数为数十级。
2. **V1**：跑一个真实模型任务 → 画布上运行中节点显示 `流式输出中…`；点节点 → 玻璃盒 →「ReAct 实时泳道」，**底部实时块**持续输出正文与思考链（可折叠），并显示当前过检关卡；跑完刷新后内容来自数据库。
3. **V1 安检**：工具块顶部六道关卡灯，被拦截时默认展开该门并给出原因。
4. **V2 显微镜**：节点玻璃盒 →「模型 I/O」→ 点「展开上下文显微镜」→ 看到六层堆叠面积图、L1/L2/L3 阈值线；下方 L1/L2/L3 逐条对照；点 `artifactRef` 弹出真实原文。
5. **V2 RAG 透视**：工作区 →「知识库」→ 检索 → 出现 BM25/向量双列与 RRF 融合漏斗（含裁剪原因）。
6. **V2 记忆与切片**：工作区 →「记忆与切片」→ 三层记忆（可放行/冻结）与文档切片边界预览。
7. **V3 编排**：发起代码任务 → 计划确认弹窗 → 切「可视化编排」→ 给两个节点填同一写入文件 → 节点变红并显示重叠文件名；把依赖连成环 → 冲突列表给出环上节点键；调整反思回边 `maxRevisions` 后结构实时重新校验。

---

## 八、结论

V0、V1、三项补丁与 V2 已按规范实现并实跑验证，V3 的「编排 + 实时结构化校验」已交付，但**保存/冻结链路与节点级异构绑定未实现**（§3.3）。V4~V6 未实施。工作区干净，全部红线自检通过。
