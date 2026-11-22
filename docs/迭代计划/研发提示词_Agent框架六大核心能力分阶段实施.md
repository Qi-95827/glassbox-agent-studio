# 研发提示词：Agent 框架六大核心能力分阶段实施（Phase 1 ~ Phase 4）

> **使用方式**：将本提示词（以及指定 Phase 段落）连同概要设计文档 `docs/概要设计/下一代_Agent框架能力升级概要设计.md` 一并交给负责编码的 AI Agent。**强烈建议按 Phase 1 → Phase 2 → Phase 3 → Phase 4 逐阶段验收，每阶段全部测试通过后再进入下一阶段。**

---

## 一、全局铁律与代码结构约束（所有阶段必须严格遵守）

你正在参与 **Glass-Box Agent Studio**（仓库根目录 `D:\software program\AI\agent-dev-workbench`）的下一代 Agent 框架升级开发。动工前请先通读 `docs/概要设计/下一代_Agent框架能力升级概要设计.md`，并无条件遵守以下工程红线：

### 1. 架构与包结构边界（严禁写散、严禁新建顶层包）
- **Maven 根目录**：`pom.xml` 在仓库根目录，所有 `mvn` 命令必须在仓库根目录执行（严禁在 `backend/` 内执行 `mvn`）。
- **严禁新建顶层包**：所有后端新类必须归位到现有顶层包的对应位置，不得随意创建新的 `dev.workbench.<newpkg>`：
  - 纯净契约与值对象（**零 Spring / JPA / AgentScope / HTTP 客户端依赖**）→ `dev.workbench.framework.{context,workflow.plan,tool,memory}`
  - 并行调度与有界反思环 → `dev.workbench.orchestration`
  - 四级上下文压缩实现 → `dev.workbench.control`
  - 工具审批与安检门增强 → `dev.workbench.tool`
  - 三层长期记忆与混合检索 → `dev.workbench.memory`
  - SSE 实时流式推送与广播 → `dev.workbench.observability`
  - 外部 SDK / MCP 协议客户端与传输层 → `dev.workbench.infrastructure.mcp`（**唯一允许出现 MCP 协议/子进程/HTTP 传输实现的位置**）
  - 实体、Spring Data JPA Repository 与 Store → `dev.workbench.persistence` & `dev.workbench.store`
- **ArchUnit 5 项守卫零容忍**：每次提交前 `ArchitectureGuardTest` 必须 5/5 全绿（顶层零循环依赖、`domain`/`framework` 纯净、Controller 严禁直接注入 Repository、`io.agentscope.*` 仅限 `infrastructure`/`persistence.agentscope`/`app`）。
- **数据库演进规范**：只允许新增 Flyway 脚本（`V7`~`V10`），**严禁修改已有 `V1`–`V6`**，严禁开启 Hibernate 自动建表。

### 2. 安全、并发与质量红线
- **严禁 `new Thread()`**：所有异步/并行执行必须通过 `AsyncConfiguration` 托管的 Spring `ThreadPoolTaskExecutor`。
- **严禁 `Thread.sleep` 死等人工审批**：HITL 工具审批必须基于数据库状态挂起 + 回调触发续跑。
- **严禁明文凭据**：MCP 认证 Header/Token 必须复用 `SecretCipher`（AES-256-GCM）加密存储，API 响应必须脱敏掩码。
- **前端规范**：函数组件、零 `dangerouslySetInnerHTML`、所有新增文案必须同步维护 `zh-CN.json` 与 `en-US.json` 中英双语；同步更新 `frontend/user-workbench/src/features/architecture/` 中受影响的架构图说明。
- **Git 边界**：**严禁执行 `git push`**，仅保留本地修改或按指示做本地验证。

---

## 二、标准代码范本与接口契约示例（请严格按此风格实现）

### 示例 1：纯净契约层（`framework.context`）——四级上下文压缩策略
> 注意：`framework` 包内严禁引入 `org.springframework.*` 或 `jakarta.persistence.*`。

```java
package dev.workbench.framework.context;

import java.util.List;
import java.util.Map;

public enum CompactionTier {
    L0_OBSERVE_ONLY,
    L1_TOOL_OFFLOAD,
    L2_SLIDING_SUMMARY,
    L3_HARD_GUARD
}

public record ContextBreakdown(
        int modelContextLimit,
        int totalEstimatedTokens,
        Map<String, Integer> segmentTokens, // SYSTEM, SKILLS, MEMORY, HANDOFF, TOOL_OUTPUTS, HISTORY
        CompactionTier appliedTier,
        int savedTokens,
        List<String> offloadedToolCallIds
) {}

public interface ContextCompactionPolicy {
    /**
     * 按 L0 -> L1(>=60%) -> L2(>=78%) -> L3(>=92%) 渐进处理。
     * 必须保证：Pinned Anchor 与最近 recencyTurns 轮完整成对消息绝不被拆散。
     */
    CompactionResult evaluateAndCompact(CompactionRequest request);
}
```

### 示例 2：有界反思环结构化评审契约（`framework.workflow.plan`）

```java
package dev.workbench.framework.workflow.plan;

import java.util.List;

public record RevisionEdge(
        String fromReviewerNodeKey,
        String toTargetNodeKey,
        int maxRevisions // 默认 2，校验器硬上限 5，严禁无界循环
) {}

public record ReviewDefect(
        String severity,     // BLOCKER | MAJOR | MINOR
        String filePath,
        String summary,
        String suggestedFix
) {}

public record ReviewVerdict(
        String decision,     // APPROVED | REVISE_REQUIRED
        List<ReviewDefect> defects,
        String summary
) {}
```

### 示例 3：工具级动态审批决策契约（`framework.tool`）

```java
package dev.workbench.framework.tool;

public enum ToolRiskLevel {
    LOW,     // 只读/搜索：自动放行
    MEDIUM,  // 已授权常规写入：按策略放行或审批
    HIGH     // 敏感配置路径 / 外部 MCP 写操作：强制触发 WAITING_TOOL_APPROVAL
}

public enum ToolApprovalAction {
    APPROVE,
    APPROVE_WITH_EDITED_ARGS, // 必须重新经过 Gate 5 授权交集复验！
    REJECT
}
```

### 示例 4：分层长期记忆混合检索评分（`framework.memory`）

```java
package dev.workbench.framework.memory;

import java.util.List;

public enum MemoryType {
    SEMANTIC,    // 项目规范/架构事实
    EPISODIC,    // 历史运行提炼的经验教训卡片（默认 CANDIDATE，经用户确认后 ACTIVE）
    PROCEDURAL   // 可晋升为私有 Skill 的方法论
}

public record MemoryMatch(
        String memoryId,
        MemoryType memoryType,
        double totalScore,
        String matchedReason, // 必须写入 AssemblySnapshot 供玻璃盒展示
        String content
) {}

/** 预留 SPI：默认使用零外部依赖混合评分，后续可无缝接入本地 Embedding 模型 */
public interface MemoryEmbeddingProvider {
    boolean isAvailable();
    float[] embed(String text);
}
```

---

## 三、分阶段实施任务书（按 Phase 逐一交付）

### Phase 1：M1 真并行分支 + 有界反思环 & M2 四级分层上下文工程
**交付范围**：
1. **Flyway `V7__parallel_reflection_and_context.sql`**：新增 `plan_revision_edges`，扩展 `node_attempts`（`revision_index`、`critic_verdict_json`）与 `model_calls`（`context_breakdown_json`、`compaction_tier`）。
2. **真并行调度**：改造 `WorkflowScheduler`，支持在写集互斥前提下并发认领多个就绪节点（受 `workbench.orchestration.max-parallel-nodes=3` 控制，使用数据库原子状态认领防重复派发）。
3. **有界反思环**：在 `PlanCandidateValidator` 校验 `RevisionEdge`（仅允许审查/检查节点指回直接上游，`1 <= maxRevisions <= 5`）；在 `orchestration/ReflectionCoordinator` 实现 `REVISE_REQUIRED` → 派生 `attempt + 1` 并注入 `CRITIC_FEEDBACK` → 超限转 `WAITING_HUMAN_REVIEW`；同步在 `DemoModel` 增加可复现的反思教学剧本。
4. **四级分层上下文引擎**：在 `control/MultiTierContextCompactor` 实现 L0 精确分桶统计、L1 工具长结果头尾保留+指针卸载、L2 Pinned Anchor + 最近 4 轮成对保留 + 结构化摘要压缩、L3 紧急硬保护；触发时落库 `CONTEXT_COMPACTED` 事件。
5. **前端玻璃盒联动**：在 `LlmTracePanel.jsx` / `AssemblyBlueprintPanel.jsx` 新增 `ContextMeterBar.jsx`（纯 CSS/SVG 分段占比条，展示压缩等级与节省比例），在 `RunFlowGraph.jsx` 用清晰虚线回边标识反思环与当前轮次（`Rev 1/2`）。

---

### Phase 2：M3 SSE 实时流式透视 & M4 工具级动态审批中断
**交付范围**：
1. **Flyway `V8__tool_approval_interrupts.sql`**：新增 `tool_approval_requests` 表。
2. **SSE 流式通道**：在 `observability/RunStreamBroker` 与 `RunStreamController` 实现 `GET /api/runs/{runId}/stream`，支持 `OwnershipService` 鉴权、`Last-Event-ID` 断点补发遗漏事件、15s 心跳保活、连接数上限与终态自动关闭；桥接 `ControlledModel` 瞬态 `MODEL_DELTA` 推送。
3. **前端流式+降级**：升级 `useRunExecution.js` 优先订阅 `EventSource`，异常连续失败 3 次自动静默降级至 1.8s 轮询。
4. **工具级动态审批（Gate 5.5）**：在 `ToolGateway` 门 5 与门 6 之间引入风险分级评估；命中 `HIGH` 时创建 `PENDING` 审批单并挂起节点为 `WAITING_TOOL_APPROVAL`（严禁阻塞工作线程）；新增审批接口 `POST /api/runs/{runId}/tool-approvals/{approvalId}/decision` 支持 `APPROVE` / `APPROVE_WITH_EDITED_ARGS`（**必须二次通过门 5 授权交集校验**）/ `REJECT`（拒绝理由作为结构化 `ToolResult` 回传模型继续执行）。
5. **前端审批抽屉**：在 `features/runs/ToolApprovalDrawer.jsx` 展示待审批工具、风险原因与可编辑参数 JSON，支持中英双语。

---

### Phase 3：M5 分层长期记忆体系（Semantic + Episodic + Procedural）
**交付范围**：
1. **Flyway `V9__layered_long_term_memory.sql`**：扩展记忆模型支持 `memory_type`、`status`（`CANDIDATE`/`ACTIVE`/`ARCHIVED`）、`source_run_id`、`path_patterns`、`confidence`、`hit_count`、`last_hit_at`。
2. **情景经验自动提炼**：在 `memory/EpisodicMemoryExtractor` 监听运行结算，自动提炼 1–3 条 `CANDIDATE` 经验卡（绝不自动生效，必须用户确认后才转为 `ACTIVE`）。
3. **混合评分检索 + SPI**：在 `memory/MemoryRetrievalService` 实现 `Lexical(0.45) + PathOverlap(0.30) + RecencyDecay(0.15) + Confidence(0.10)` Top-K 召回，预留 `MemoryEmbeddingProvider` SPI；装配时将 `MemoryMatch`（含得分与召回理由）写入 `AgentAssemblySnapshot`。
4. **前端体验**：升级 `WorkspaceMemoryPanel.jsx` 支持三层记忆筛选、候选经验一键「采纳 / 忽略」、以及一键「晋升为私有 Skill 草稿」；在玻璃盒「装配配方」展示每条注入记忆的命中得分与理由。

---

### Phase 4：M6 MCP 客户端接入与六道安检门统一治理
**交付范围**：
1. **Flyway `V10__mcp_server_registry.sql`**：新增 `user_mcp_servers` 与 `user_mcp_tools` 表（凭据字段走 `SecretCipher` AES-256-GCM 加密）。
2. **MCP 协议隔离适配层**：在 `infrastructure/mcp/` 实现标准 JSON-RPC 2.0 客户端，支持 `STDIO`（命令白名单与环境隔离）与 `STREAMABLE_HTTP`，实现 `initialize`、`tools/list`、`tools/call` 及超时熔断。
3. **强制收口至 Tool Gateway**：通过 `McpToolProvider` 将已启用 MCP 工具以 `mcp::<serverSlug>::<toolName>` 暴露给装配器，**100% 路由过 Tool Gateway 六道安检门 + Gate 5.5 高危审批**，严禁绕过网关直连。
4. **前端管理界面**：在 `features/settings/` 新增 `McpServerPanel.jsx` & `McpServerDialog.jsx`（紧邻 BYOK 模型配置），支持添加 Server、一键探测工具清单、逐工具配置风险等级（`LOW` / `HIGH`）与启停开关，密钥零明文回显。

---

## 四、每阶段强制验收自检清单（交付前必跑）

每完成一个 Phase，必须执行以下全部检查并汇报真实输出数据：

1. **后端构建与全量测试（仓库根目录执行）**：
   - `mvn test`：0 failures、0 errors。
   - 明确确认 `ArchitectureGuardTest` **5/5 通过**。
   - 明确确认 Flyway 迁移测试通过且最新版本号匹配（V7 / V8 / V9 / V10）。
2. **前端规范与构建（`frontend/user-workbench` 目录执行）**：
   - `npm run format:check`：全部通过。
   - `npm run lint`：0 error / 0 warning。
   - `npm test`：原有 51 个单测零破坏 + 本 Phase 新增单测全部通过。
   - `npm run build`：退出码 0。
3. **中英双语与玻璃盒一致性**：
   - 新增 UI 与后端错误提示均同时具备 `zh-CN` 和 `en-US`。
   - 新增运行事件/指标均可在玻璃盒面板中直观观察。
4. **提交边界**：
   - 仅汇报改动清单与测试数据，**严禁执行 `git push`**。
