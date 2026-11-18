# 任务 2 交付记录 · 模拟 Bug 动态验证

> 记录日期：2026-11-18。工作区 `D:\software program\AI\agent-dev-workbench`，基线提交 `6350831` 加既有未提交改动。对应交接文档任务 2 与 A02、A05、A07、A09、A12、A14、A20—A22、A25。只陈述实际执行和验证的内容。

## 1. 完成范围

| 位置 | 改动 |
| --- | --- |
| `orchestration/BuiltInWorkflows.java` | 新增 `bug-fix` 模板（初版 v1，审查加固后升级 v2）：复现检查 → 定位 → 授权修复 → 回归检查 → 独立审查。 |
| `framework/workflow/plan/WorkflowTemplate.java` | 新增 `WORKSPACE_BUG_FIX` 范围：要求 Bug 入口、受支持模拟 Bug、写入授权、工作区与文件集合，并纳入授权摘要。 |
| `bug/BugReproductionCheck.java`、`bug/BugRegressionCheck.java` | 复用 `BugService.runForTask` 的受控容器检测；通过运行控制 `guard` 响应取消、暂停和时间限额。 |
| `bug/BugCheckResults.java` | 将检测状态映射为检查通过、失败或阻塞，保留检测记录、代码指纹和输出摘要。 |
| `bug/BugService.java` | 增加受支持 Bug 查询；既有检测、只读挂载、无网络、资源限制和超时不变。 |
| `framework/workflow/check/SystemCheckResult.java`、`SystemCheckRequest.java` | 检查结果可携带外部权威证据标识；检查请求携带运行控制。 |
| `task/WorkflowExecutionRepository.java` | 持久化外部证据标识；Bug 修复完成时明确等待用户核对；取消时检查标记中断。 |
| `orchestration/RunService.java` | 创建任务时核对受支持模拟 Bug。 |
| `backend/src/main/resources/schema.sql` | `workflow_checks.external_evidence_id`；`workflow_plans` 范围约束升级到 v3。 |
| `frontend/user-workbench/src/main.jsx` 等 | 新增模板选项与兼容性规则；Bug 面板一键创建时带入模板和写入授权；计划面板显示检查状态与证据标识。 |
| 测试 | 新增 `BugDynamicWorkflowIntegrationTest` 7 个用例，含真实 Docker 流程和 HTTP 创建入口。 |

用户入口：任务创建选择「模拟 Bug 复现、修复与回归」；或从“模拟 Bug 验证”面板点击“基于此 Bug 创建 Agent 任务”，系统自动带入 `bug-fix` 模板、写入授权和 Bug 入口。

## 2. 设计与兼容

- 设计文档：[动态 Bug 修复工作流设计](../概要设计/重构_动态Bug修复工作流设计.md)。
- 检查节点不创建 Agent 实例、模型或工具调用；复现失败、缺信息、环境不可用或过期时，定位、修复、回归和审查全部不启动。
- `bug_checks` 继续作为检测证据的唯一权威来源，保存运行、Bug、工作区、类型、状态、输出和代码指纹；`workflow_checks.external_evidence_id` 只保存引用。
- 回归检查复用同一固定命令并在执行前后核对工作区指纹；指纹变化标记 `STALE`，不能作为通过证据。
- 回归通过且审查完成后运行仍为 `BLOCKED`，原因明确等待用户核对；不自动合并、不写反馈、不关闭 Bug。
- 兼容 Bug 流程保留；新模板为新增选项。历史计划、检查记录和数据库记录可继续读取；新增列在旧记录中为空。

## 3. 验收证据

| A 编号 | 复现方式 | 实际结果 |
| --- | --- | --- |
| A02 | `realContainerEvidenceDrivesReproductionFixAndRegression` | 只有写入节点执行修改；审查独立于写入；用户决定最终验收；原仓库未被修改。 |
| A05 | `bugTemplateRequiresBugEntryWriteAuthorizationAndAuthorizedFiles`、计划摘要复核 | 缺少写入授权、文件集合或未知 Bug 时服务端拒绝；计划确认和认领重新校验摘要。 |
| A07 | `failedRegressionBlocksIndependentReviewAndKeepsWriteEvidence` | 回归失败保留写入记录，不报告修复，不进入审查。 |
| A09 | 运行详情 `workflowChecks`、`bug_checks` 与计划节点 | 检查、Agent 实例和实际调用可分别定位；检查不计入 Agent 或模型调用。 |
| A12 | 检查节点执行与未知检查拒绝（任务 1 用例） | 检查由真实固定检测处理，不伪造 Agent。 |
| A14 | 复现缺信息、环境不可用、未复现、过期用例 | 运行阻塞并给出原因，下游不启动，状态不显示为通过。 |
| A20 | `cancellationDuringReproductionLeavesInterruptedCheck` | 取消后检查标记 `INTERRUPTED`，不显示为已验证；无 Agent 或模型调用。 |
| A21 | 检查输入只含本次运行的上游结果；Bug 检测按运行、Bug、工作区归属校验 | 跨运行检测不能进入本次检查；过期指纹不通过。 |
| A22 | 复现/回归状态映射 | 拒绝、环境错误、超时、中断、过期、失败与通过分别记录。 |
| A25 | 事件 `CHECK_*`、运行详情与报告 | 可区分检查通过、失败、未执行以及 Agent 调用。 |

执行命令与结果：

```text
$env:RUN_DOCKER_TESTS = 'true'
mvn test
Tests run: 174, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS

cd frontend/user-workbench && npm run build
vite build 通过，仅保留包体积警告

git diff --check
无空白错误
```

启用的真实环境：Docker 29.4.3，镜像 `python:3.12-alpine`。两个原有 Docker 用例和新的动态容器用例均实际执行；仅跳过受 Windows 权限限制的符号链接用例。

真实容器用例观测结果：

- 5 个计划节点全部完成：复现检查、定位、修复、回归检查、独立审查。
- 2 条系统检查均通过，`external_evidence_id` 指向本次 `bug_checks` 记录。
- 复现记录状态 `FAILURES_OBSERVED`，回归记录状态 `PASSED`；两者代码指纹不同，回归指纹等于当前工作区指纹。
- 授权文件在工作区副本中更新，原仓库保持不变；未自动写入 Bug 反馈或关闭 Bug。
- 运行终态 `BLOCKED`，原因包含“回归检查通过”和“不会自动合并”。

## 4. 未关闭事项

- 只读 Bug 任务仍走兼容流程；本次只交付带写入授权的动态修复模板。
- 真实 Bug 平台、更多语言、不可复现分诊和容器镜像治理不在本期范围。
- 工作台的检查统计和评测反例属于任务 5；本轮只补齐运行详情、证据引用和报告串联。
- 本任务验证未使用真实 DeepSeek；Agent 调用使用模拟模型，容器检测使用真实 Docker。全局一次真实只读验证见[真实 DeepSeek 验证记录](真实DeepSeek验证记录_2026-11-18.md)。

## 5. 后续审查修复

- 独立审查发现快捷入口默认勾选写权限：已改为默认关闭并默认开启计划审阅，新增前端验收用例。
- 独立审查发现检查通过后仍可能使用过期证据：`bug-fix` 升级到 v2，修复节点直接依赖复现检查；认领下游前复核 Bug、工作区和当前代码指纹，过期时 `BLOCKED` 并显示“证据已过期”。
- 详见[审查修复记录](审查修复记录_2026-11-18.md)。
