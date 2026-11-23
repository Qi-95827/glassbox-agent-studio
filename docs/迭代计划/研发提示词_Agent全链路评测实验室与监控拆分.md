# 研发提示词：Agent 全链路评测实验室（Eval Lab V2）与系统监控拆分

> **使用方式**：将本提示词连同概要设计文档 `docs/概要设计/下一代_Agent全链路评测实验室概要设计.md` 一并交给负责编码的 AI Agent。支持**单次指令连续完成 Phase 1 → Phase 2 → Phase 3**，但**每完成一个 Phase 必须跑通全量测试并做一次本地 `git commit` 检查点**，再进入下一阶段。

---

## 一、全局铁律与代码结构约束（所有阶段必须严格遵守）

你正在为 **Glass-Box Agent Studio**（仓库根目录 `D:\software program\AI\agent-dev-workbench`）开发「全链路评测实验室（Eval Lab V2）」并将「系统监控」拆分为独立一级菜单。动工前请先完整阅读 `docs/概要设计/下一代_Agent全链路评测实验室概要设计.md`，并严格遵守以下红线：

### 1. 架构与目录红线
- **Maven 根目录**：所有 `mvn` 命令必须在仓库根目录执行（`pom.xml` 在根目录，严禁在 `backend/` 内执行 `mvn`）。
- **严禁新建后端顶层包**：所有后端新增代码必须归位到现有包中：
  - 纯净评测契约（**零 Spring / JPA / AgentScope 依赖**）→ `dev.workbench.framework.evaluation`
  - 评测实验室服务、6 大执行器、双轨裁判、Hub 下载与 A/B 对照 → `dev.workbench.evaluation`（子包 `runner`、`grader`、`dataset`）
  - 实体与 Spring Data JPA Repository → `dev.workbench.persistence` & `dev.workbench.store`
  - 若需要调用其他顶层包（如 `memory`、`tool`、`orchestration`）且可能引发 ArchUnit 顶层包环依赖，**必须像 Phase 3 那样在 `framework` 定义 Port 接口反转依赖**，确保 `ArchitectureGuardTest` 始终 **5/5 通过**。
- **Flyway 不可变原则**：严禁修改已有 `V1`–`V10`，仅新增 `V11__eval_lab_v2.sql` 与 `V12__eval_hub_and_datasets.sql`。
- **大数据集隔离**：必须在根目录 `.gitignore` 中添加 `/data/eval-datasets/`；仓库内 `backend/src/main/resources/eval-packs/` 仅放精选微集 JSONL，总大小 `< 200KB`。

### 2. 真实性与安全红线
- **严禁伪造评测结果**：6 大 `EvalCategoryRunner` 必须真实调用系统对应的核心组件（`MultiTierContextCompactor`、`MemoryRetrievalService`、`ToolGateway`、`PlanCandidateValidator`、`WorkflowScheduler` / `ControlledModel`），所有指标由真实执行轨迹与断言计算得出。
- **防 SSRF 与防烧费**：Benchmark Hub 在线下载必须严格校验 URL 仅命中 `benchmark-hub-catalog.json` 白名单（禁止任意内网 URL 下载），校验最大文件体积与 SHA-256；评测运行必须支持 `sampleLimit` 与 `maxCostUsd` 硬预算保护。
- **异步与并发**：严禁 `new Thread()`，评测批次异步执行统一走 `AsyncConfiguration` 托管线程池。
- **前端与双语**：函数组件、零 `dangerouslySetInnerHTML`、雷达图使用纯 React SVG（零重型图表库依赖）、所有新增文案同步维护 `zh-CN.json` 与 `en-US.json`、同步更新 `frontend/user-workbench/src/features/architecture/` 架构图谱。
- **Git 边界**：每 Phase 全绿后仅做本地 `git commit`，**全程严禁执行 `git push`**。

---

## 二、标准代码范本与契约示例（请严格按此风格实现）

### 示例 1：纯净契约层（`dev.workbench.framework.evaluation`）
> 注意：本包严禁引入 `org.springframework.*` 或 `jakarta.persistence.*`。

```java
package dev.workbench.framework.evaluation;

import java.util.List;
import java.util.Map;

public enum EvalCategory {
    CODING_REFLECTION,      // 1. 端到端代码与有界反思
    TOOL_MCP,               // 2. 工具调用与 MCP 治理
    CONTEXT_COMPACTION,     // 3. 四级上下文压缩 (NIAH)
    LONG_TERM_MEMORY,       // 4. 三层长期记忆与混合召回
    PLANNING_MULTI_AGENT,   // 5. 多 Agent 规划与交接协作
    SAFETY_INJECTION        // 6. 安全边界与越权/注入攻防
}

public record EvalExperimentConfig(
        String modelMode,              // mock | deepseek | byok
        String modelConnectionId,      // 可选 BYOK ID
        boolean enableCompaction,      // 消融开关：四级上下文压缩
        boolean enableLongTermMemory,  // 消融开关：三层长期记忆
        boolean enableReflection,      // 消融开关：有界反思环
        boolean enableMcp,             // 消融开关：MCP 工具源
        int repeatK,                   // 1..3，用于计算 Pass^k
        int sampleLimit,               // 单类最大抽样数，默认 10
        Double maxCostUsd,             // 批次费用硬上限，可为空
        String judgeModelConnectionId  // 可选 LLM-as-a-Judge BYOK ID
) {}

public record EvalSample(
        String id,
        EvalCategory category,
        String sourcePack,             // mini | hub:<packId> | custom:<datasetId>
        String titleZh,
        String titleEn,
        String prompt,
        Map<String, Object> setup,     // 如 needles[], distractorMemories[], workspaceFiles, targetTool, attackPayload
        List<Map<String, Object>> assertions,
        List<String> judgeRubric       // 可选 LLM-as-a-Judge 评分项
) {}

public interface EvalCategoryRunner {
    EvalCategory category();
    EvalCaseExecution run(EvalSample sample, EvalExperimentConfig config, String ownerScopeKey);
}
```

### 示例 2：内置精选微集 JSONL 样本格式（`resources/eval-packs/mini-context-compaction.jsonl`）

```json
{"id":"ctx-01","category":"CONTEXT_COMPACTION","sourcePack":"mini","titleZh":"L1 卸载后关键字段约束留存","titleEn":"Constraint retention after L1 tool offload","prompt":"请根据对话中已确认的约束，输出最终接口响应 JSON。","setup":{"targetUtilization":0.65,"haystackRounds":8,"toolBlobChars":2000,"needles":["trace_epoch=9482","forbidden_file=AuthGate.jsx"]},"assertions":[{"type":"COMPACTION_TIER_AT_LEAST","expected":"L1_TOOL_OFFLOAD"},{"type":"NEEDLE_RETENTION_MIN","expected":1.0},{"type":"ZERO_API_400","expected":true}],"judgeRubric":["保留全部关键约束且无虚构字段"]}
```

### 示例 3：LLM-as-a-Judge 严格输出契约（外置双语 Prompt `prompts/eval-judge.txt` & `prompts/en/eval-judge.txt`）

```json
{
  "score": 4,
  "passed": true,
  "rubricChecks": [
    { "criterion": "保留全部关键约束且无虚构字段", "passed": true, "reason": "输出准确包含 trace_epoch=9482 且未修改 AuthGate.jsx。" }
  ],
  "summary": "压缩后关键信息完整保留，符合预期。"
}
```

---

## 三、分阶段实施任务书（强制按 Phase 1 → Phase 2 → Phase 3 逐阶段闭环）

### Phase 1：菜单拆分 + V2 评测底座 + 6 类内置精选微集与双轨裁判
1. **前后端导航拆分**：
   - 左侧导航 `Sidebar.jsx` 拆分为两个独立一级入口：**「评测实验室（`/evaluations`）」** 与 **「系统监控（`/monitoring`）」**。
   - 新增 `features/monitoring/MonitoringPage.jsx` 承接原 `MonitoringPanel`（保留全部监控图表、时间窗/状态/模型筛选、5 步诊断抽屉与「架构原理」按钮）。
   - `App.jsx` 注册 `/monitoring` 路由，原 `/evaluations` 升级为评测实验室（保留经典底座回归套件作为其中一个 Tab，零破坏老接口与老单测）。
2. **Flyway `V11__eval_lab_v2.sql`**：创建 `eval_lab_runs` 与 `eval_lab_case_results` 表及对应 Entity / JPA Repository。
3. **6 类内置微集**：在 `backend/src/main/resources/eval-packs/` 创建 6 个精选 JSONL 文件（每类 4 题，共 24 题，总计 `< 200KB`），由 `BuiltInMiniPackLoader` 加载。
4. **6 大 `EvalCategoryRunner`**：在 `evaluation/runner/` 分别实现 6 类真实执行器，复用系统真实组件并计算每类核心指标（`Pass@1`、`Pass^k`、Needle 留存率、Token 压缩率、`Recall@K`、`MRR`、`CANDIDATE` 零泄露率、越权拦截率等）。
5. **双轨裁判**：实现 `CodeAssertionGrader`（确定性断言）与 `LlmJudgeGrader`（读取外置双语 `eval-judge.txt`，支持可选 BYOK 模型打分并单独计量裁判开销）。
6. **REST API 与发起弹窗**：`POST /api/eval-lab/runs`（异步派发）、`GET /api/eval-lab/runs`、`GET /api/eval-lab/runs/{id}`（严格走 `OwnershipService`）；前端实现「新建评测实验弹窗（选类别/模型/消融开关/裁判/抽样上限/费用上限）」与批次列表页。
7. **Phase 1 检查点**：跑通全部前后端自检 + 新增单测（6 大 Runner + 双轨裁判 + Flyway V11 + ArchUnit 5/5）→ 本地 `git commit -m "feat: complete eval lab phase 1 monitoring split and 6-category engine"`。

---

### Phase 2：Benchmark Hub 按需下载中心 + 自定义评测集与 JSONL 导入
1. **Flyway `V12__eval_hub_and_datasets.sql`**：创建 `eval_benchmark_packs`、`eval_custom_datasets`、`eval_custom_samples`；在根目录 `.gitignore` 追加 `/data/eval-datasets/`。
2. **Benchmark Hub 清单与受控下载**：
   - 在 `resources/eval-packs/benchmark-hub-catalog.json` 登记 6 大权威开源集（SWE-bench Lite、BFCL v3、RULER/NIAH、LongMemEval、GAIA L1、AgentDojo），包含来源 URL、协议、类别、预计题量与说明。
   - `EvalBenchmarkHubService` 实现白名单 URL 下载至 `data/eval-datasets/<packId>/`、SHA-256 校验、大小上限保护；实现 `BenchmarkPackAdapter` 将开源格式标准化为 `EvalSample`。
   - 同时提供 **离线 JSONL 上传导入接口** `POST /api/eval-lab/hub/{packId}/import` 作为网络受限时的 100% 兜底。
3. **用户自定义评测集**：支持按 6 大类别创建/编辑/删除自定义评测集与样本，支持 JSONL 批量导入与导出，按账号归属隔离；发起评测时可自由选择「内置微集 / 已下载 Hub 集 / 我的自定义集」。
4. **前端界面**：实现 `BenchmarkHubPanel.jsx`（6 大权威集卡片：状态、一键下载、离线导入 JSONL、一键开跑）与自定义评测集编辑器。
5. **Phase 2 检查点**：跑通全部前后端自检 + 新增单测（URL 白名单拦截、离线导入适配器、自定义数据集隔离、Flyway V12 + ArchUnit 5/5）→ 本地 `git commit -m "feat: complete eval lab phase 2 benchmark hub download and custom datasets"`。

---

### Phase 3：六维雷达看板 + 玻璃盒逐题穿透 + A/B 消融对照 + 架构图谱同步
1. **纯 SVG 六维能力雷达图（`EvalRadarChart.jsx`）**：
   - 遵循 Google M3 浅色语义色板，展示 6 维得分（0–100%），支持单批次模式与 **A vs B 双图层叠加对比模式**，hover 显示精确指标与样本数。
2. **逐题玻璃盒内部穿透抽屉（`EvalCaseDetailDrawer.jsx`）**：
   - 点击任意评测题，清晰展开：**题目输入 → 模型原始输出 → 工具安检门判定 → 上下文压缩前后 Token 与 Needle 留存 → 记忆召回得分明细 → 双轨裁判逐条理由**；若关联真实运行 `linkedRunId`，提供 **「打开完整运行玻璃盒 →」** 深链按钮。
3. **A/B 消融对照（`EvalCompareService` + `EvalAbComparePanel.jsx`）**：
   - 选择批次 A 与批次 B，后端自动比对实验开关差异、6 维指标 Δ 差值、成本与延迟 Δ 差值，以及逐题 `IMPROVED / REGRESSED / UNCHANGED` 状态。
4. **同步更新架构图谱**：更新 `frontend/user-workbench/src/features/architecture/` 中 `08 · 交付与监控` 及相关说明，同步反映「评测实验室 + 系统监控拆分」。
5. **Phase 3 检查点**：跑通全部前后端自检 + 新增单测（A/B 对比服务、`EvalRadarChart`、`EvalCaseDetailDrawer`、全量 55+ 前端用例 + 全量后端测试 + ArchUnit 5/5）→ 本地 `git commit -m "feat: complete eval lab phase 3 radar dashboard glassbox drilldown and AB compare"`。

---

## 四、每阶段强制验收自检清单（严禁跳过）

1. **后端（仓库根目录）**：`mvn test` 全部通过（0 failures, 0 errors）；明确汇报 `ArchitectureGuardTest` **5/5** 与 Flyway 版本（V11 / V12）。
2. **前端（`frontend/user-workbench`）**：`npm run format:check`、`npm run lint`（0 error / 0 warning）、`npm test`、`npm run build` 全部通过。
3. **双语与提交**：所有新增文案中英双语同步；仅本地 `git commit`，**严禁执行 `git push`**；最终按 Phase 1–3 汇总改动文件、本地 commit 哈希与真实测试数据。
