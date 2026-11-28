/**
 * 前后端共享契约（前端侧唯一来源）。
 *
 * <p>文件里的每个字段只有两种来源，不做推测：
 *
 * <ul>
 *   <li>后端真实投影：Java record（`delivery/ExecutionViewService`、`timeline/RunObservationService`、
 *       `framework/context/ContextBreakdown`、`evaluation/EvalCompareService` 等）与 Map 投影
 *       （`persistence/repository/RunData.apiFields()`、`orchestration/WorkbenchTaskService.run()`）。
 *   <li>现有前端组件的真实读取点：迁移前是纯 JS，字段名已经被运行时使用固定下来。
 * </ul>
 *
 * <p>本层不猜形状：没逐个核对的后端结构保留 `unknown`（见 `JsonObject`）；后端可能缺省的字段写成可选属性或
 * `| null`，并在注释里说明缺省场景。集合字段在后端多数由 record 紧凑构造器归一成非空集合，但历史运行仍可能
 * 出现 `null`，所以读取侧继续保留 `?? []` 的写法。
 */

// ───────────────────────────── 基础标量 ─────────────────────────────

/** 后端 `dev.workbench.domain.RunStatus` 的线上取值。 */
export type RunStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'PAUSED'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'
  | 'LIMITED'
  | 'BLOCKED'
  | 'INTERRUPTED';

/** 后端 `PlanNodeStatus` 的线上取值。 */
export type PlanNodeStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'WAITING_TOOL_APPROVAL'
  | 'COMPLETED'
  | 'REUSED'
  | 'FAILED'
  | 'CANCELLED'
  | 'LIMITED'
  | 'INTERRUPTED'
  | 'SKIPPED';

/** 后端 `CompactionTier` 的线上取值；`L0` 只观测，其余逐级生效。 */
export type CompactionTier =
  'L0_OBSERVE_ONLY' | 'L1_TOOL_OFFLOAD' | 'L2_SLIDING_SUMMARY' | 'L3_HARD_GUARD';

/**
 * 尚未逐个核对的 JSON 对象行。
 *
 * <p>刻意保留 `unknown`：与其先猜一批字段、让调用方误以为有类型保障，不如显式标注「还没核对」。
 * 需要时按真实后端定义逐项收窄。
 */
export type JsonObject = Record<string, unknown>;

// ───────────────────────────── 运行：列表与详情 ─────────────────────────────

/**
 * 运行列表行 / 运行详情里的 `run` 对象。
 *
 * <p>来源：`persistence/repository/RunData.apiFields()`。后端保留历史 API 字段名（全大写下划线），
 * 前端 `RunListView`、`RunIterationCard`、`RunOutputsPanel` 等组件正是按这些键读取
 * （`run.ID`、`run.STATUS`、`run.ELAPSED_MS`、`run.CREATED_AT`…），所以这里照真实线上一字不差地声明，
 * 不做 camelCase 重命名。
 *
 * <p>camelCase 形式的等价摘要见 {@link RunExecutionSummary}（来自 `/runs/{id}/execution-view` 的投影）。
 */
export interface RunSummary {
  ID: string;
  TASK_ID: string;
  /** 父运行；只有恢复子运行才有值。 */
  PARENT_ID: string | null;
  STATUS: RunStatus;
  /** 停止或阻塞的人类可读原因；正常完成时为 null。 */
  REASON: string | null;
  /** 最终交付正文；未产出时为 null。 */
  RESULT: string | null;
  /** 已用轮次与工具次数；旧运行列可能为 null，读取侧仍按 `?? 0` / `?? '—'` 处理。 */
  ROUNDS: number | null;
  TOOLS: number | null;
  MAX_ROUNDS: number | null;
  MAX_TOOLS: number | null;
  MAX_SECONDS: number | null;
  ELAPSED_MS: number | null;
  CREATED_AT: string | null;
  UPDATED_AT: string | null;
  MAX_PER_TOOL: number | null;
  MAX_REPORTED_TOKENS: number | null;
  MAX_IDENTICAL_TOOLS: number | null;
  MAX_AGENT_ROUNDS: number | null;
  MAX_AGENT_TOOLS: number | null;
  RECOVERY_DISPATCH: string | null;
  /**
   * 停止原因编码。后端 `RunStateStore.run(id)` 用它推导阻塞分类；取值由服务端枚举决定，
   * 本层不枚举具体常量，避免与后端枚举漂移。旧运行可能缺省。
   */
  STOP_CAUSE: string | null;
  /**
   * 阻塞分类。`RunStateStore.run(id)` 只在「状态 + 停止原因能归入阻塞类」时补写该键，
   * 因此是可选属性（列表行 `runs(taskId)` 不写入它）。
   */
  BLOCK_CATEGORY?: string;
}

/**
 * 运行的 camelCase 摘要：`ExecutionView` 头部字段的公共基类。
 *
 * <p>来源：`ExecutionViewService.ExecutionView`（`GET /api/runs/{id}/execution-view`）。
 * 任务描述里「id/taskId/status/stopCause/…」的 camelCase 诉求对应这里；注意运行标识的字段名是 `runId`。
 */
export interface RunExecutionSummary {
  taskId: string;
  runId: string;
  parentRunId: string | null;
  runStatus: RunStatus;
  stopCause: string | null;
  reason: string | null;
  result: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  elapsedMs: number;
  rounds: number;
  tools: number;
  taskVersion: number;
}

/**
 * 同任务轮次项。
 *
 * <p>来源：`RunRoundsService.Round`（`GET /api/tasks/{taskId}/runs`，即 `runsApi.rounds()`）。
 * `RunRoundsBar` / `RunDetailPage` 直接按这四个键读取。
 */
export interface RunRound {
  runId: string;
  roundIndex: number;
  status: RunStatus;
  /** 本轮调改意见摘要；首轮或未提交调改时为 null。 */
  feedbackSummary: string | null;
}

/**
 * 任务行（运行详情里的 `task` 对象）。
 *
 * <p>来源：`TaskRecordService.task(taskId, status)` 的行投影。这里只声明前端真实读取的列，
 * 任务行还有更多列未被前端使用，故不全部罗列，也保留索引签名以容纳未声明的列。
 */
export interface TaskRow {
  ID: string;
  GOAL: string;
  TASK_STATUS: string;
  MODEL_MODE: string | null;
  /** 任务版本号；调改会自增，用于乐观并发校验。 */
  VERSION?: number;
  ARCHIVED_AT?: string | null;
  BUG_ID?: string | null;
  WORKSPACE_ID?: string | null;
  WORKSPACE_DELIVERED?: boolean;
  [key: string]: unknown;
}

/** 计划节点预算（`WorkflowNodeBudget`）：两上限都可单独缺省，但不会同时缺省。 */
export interface WorkflowNodeBudget {
  maxModelCalls?: number | null;
  maxToolCalls?: number | null;
}

/**
 * 运行详情里的计划节点。
 *
 * <p>来源：`framework/workflow/plan/PlanNode`（`PlanService.find(runId)` → `detail.planNodes`）。
 * `PlanAdjustmentEditor` 读取 `key` / `label` / `dependencyIds` / `budget`，其余字段保持后端原名。
 */
export interface PlanNodeView {
  id: string;
  runId: string;
  sequence: number;
  key: string;
  label: string;
  role: string | null;
  prerequisite: string | null;
  status: PlanNodeStatus;
  phase: string | null;
  kind: string;
  workspaceRead: boolean;
  workspaceWrite: boolean;
  agentId: string | null;
  sourceAgentId: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  dependencyIds: string[];
  planVersionId: string | null;
  definitionRef?: unknown;
  definitionSha256?: string | null;
  checkRef?: unknown;
  agentBinding?: unknown;
  budget?: WorkflowNodeBudget | null;
}

/**
 * 计划版本（运行详情里的 `workflowPlan`）。
 *
 * <p>来源：`WorkflowPlan` record。`PlanAdjustmentEditor` 用 `reviewRequired` / `approvedAt` /
 * `graphSha256` 判断是否还能调整并做乐观并发校验。
 */
export interface WorkflowPlanView {
  id: string;
  version: number;
  templateId: string;
  templateVersion: number;
  reviewRequired: boolean;
  approvedAt: string | null;
  graphSha256: string | null;
  sourceCheckpointId: string | null;
  scope: string;
  authorizationSha256: string | null;
  maxAttempts: number;
}

/** 运行详情里的 Agent 装配记录；`snapshot` 内部结构未逐个核对，保留 unknown。 */
export interface AgentAssemblyRow {
  nodeId?: string | null;
  agentId?: string | null;
  attemptNo?: number | null;
  snapshot?: {
    components?: {
      model?: { mode?: string | null } | null;
      [key: string]: unknown;
    } | null;
    [key: string]: unknown;
  } | null;
  [key: string]: unknown;
}

/** 检查点摘要：`workflowCheckpoints` 与 `checkpoints` 两处都用，故同时保留两套历史键名。 */
export interface WorkflowCheckpointSummary {
  id?: string;
  ID?: string;
  available?: boolean;
  VALID?: boolean;
  /** `workflowRecovery.checkpoints()` 使用大写阶段名。 */
  STAGE?: string;
  stage?: string;
  completedNodes?: number;
  [key: string]: unknown;
}

/**
 * 运行详情聚合（`GET /api/runs/{id}`）。
 *
 * <p>键集合来源：`orchestration/WorkbenchTaskService.run(id, includeEvents)` 的逐个 `detail.put(...)`。
 * 其中 `workflowPlan` / `workflowAttempts` / `workflowChecks` / `workflowHandoffs` /
 * `workflowCheckpoints` 只在存在计划版本时写入，故为可选属性。
 */
export interface RunDetail {
  run: RunSummary;
  task: TaskRow;
  planNodes: PlanNodeView[];
  workflowPlan?: WorkflowPlanView;
  agents: JsonObject[];
  agentAssemblies?: AgentAssemblyRow[];
  tools: JsonObject[];
  models: JsonObject[];
  events: RunEvent[];
  checkpoints: WorkflowCheckpointSummary[];
  workflowCheckpoints?: WorkflowCheckpointSummary[];
  handoffs: JsonObject[];
  runs: RunSummary[];
  /** 同一任务下的历史运行轮次（老的运行列表），与 `runsApi.rounds()` 的 `RunRound` 不是同一形状。 */
  roleBudgets?: JsonObject[];
  toolBudgets?: JsonObject[];
  entryPolicy?: JsonObject | null;
  entryRoute?: JsonObject | null;
  sourceArtifacts?: JsonObject[];
  workspaceScope?: JsonObject | null;
  workflowMetrics?: JsonObject | null;
  snapshots?: JsonObject[];
  recoveryLease?: JsonObject | null;
  costEstimate?: JsonObject | null;
  externalActions?: JsonObject[];
  workflowAttempts?: JsonObject[];
  workflowChecks?: JsonObject[];
  workflowHandoffs?: JsonObject[];
}

// ───────────────────────────── 执行视图（execution-view） ─────────────────────────────

/** 有界反思回边；`revisionCount` 是已触发次数。 */
export interface RevisionEdgeView {
  reviewerNodeId: string;
  targetNodeId: string;
  maxRevisions: number;
  revisionCount: number;
}

/** 计划版本摘要。 */
export interface ExecutionPlanView {
  id: string;
  version: number;
  templateId: string;
  templateVersion: number;
  scope: string;
  reviewRequired: boolean;
  approvedAt: string | null;
  sourceCheckpointId: string | null;
  graphSha256: string | null;
  maxAttempts: number;
}

/** 装配组件（技能/工具各一份投影）。 */
export interface BindingComponentView {
  id: string;
  version: number;
  name: string;
  kind: string;
  sha256: string;
}

/** 执行视图里的节点（camelCase 投影，与 `PlanNode` 不是同一形状）。 */
export interface NodeView {
  id: string;
  planVersionId: string | null;
  sequence: number;
  key: string;
  label: string;
  kind: string;
  phase: string | null;
  role: string | null;
  status: PlanNodeStatus;
  agentId: string | null;
  sourceAgentId: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  workspaceRead: boolean;
  workspaceWrite: boolean;
  maxModelCalls: number | null;
  maxToolCalls: number | null;
  definitionId: string | null;
  definitionVersion: number | null;
  checkId: string | null;
  checkVersion: number | null;
  dependencyIds: string[];
  ready: boolean;
  waitingForNodeIds: string[];
  attemptCount: number;
  checkCount: number;
  agentKind: string | null;
  agentName: string | null;
  agentSource: string | null;
  agentReason: string | null;
  agentSpecSha256: string | null;
  agentSkills: BindingComponentView[];
  agentTools: BindingComponentView[];
}

/** 计划 DAG 的边。 */
export interface EdgeView {
  fromNodeId: string;
  toNodeId: string;
}

/** 此刻正在进行的活动项。 */
export interface FocusItem {
  nodeId: string | null;
  nodeLabel: string | null;
  attemptId: string | null;
  checkId: string | null;
  agentId: string | null;
  activityKind: string | null;
  activityId: string | null;
  activityLabel: string | null;
  startedAt: string | null;
  status: string | null;
}

/** 焦点容器；`active` 可能为空数组。 */
export interface FocusView {
  active: FocusItem[];
}

/** 阻塞原因；`message` 是给人看的，`code` 才是稳定分支依据。 */
export interface BlockingView {
  code: string;
  message: string;
  scope: string | null;
  nodeId: string | null;
  nodeLabel: string | null;
  blockingNodeIds: string[];
}

/** 可用动作；`actionCode` 是稳定标识，`enabled` 为 false 时 `disabledReason` 说明原因。 */
export interface ActionView {
  actionCode: string;
  label: string;
  enabled: boolean;
  disabledReason: string | null;
  runId: string | null;
  planVersionId: string | null;
  checkpointId: string | null;
  targetLabel: string | null;
}

/** 恢复来源中被复用的节点。 */
export interface ReusedNodeView {
  nodeId: string;
  key: string;
  label: string;
  sourceAgentId: string | null;
}

/** 从检查点继承的消耗量；三项都可能缺省。 */
export interface InheritedView {
  rounds: number | null;
  tools: number | null;
  elapsedMs: number | null;
}

/** 恢复视图；只有存在父运行或来源检查点时才下发。 */
export interface RecoveryView {
  parentRunId: string | null;
  sourceCheckpointId: string | null;
  reusedNodes: ReusedNodeView[];
  inherited: InheritedView | null;
  newModelCalls: number;
  newToolCalls: number;
}

/** 补充信息请求；不存在时整体为 null。 */
export interface SupplementView {
  id: string;
  status: string;
  kind: string;
  reasonCode: string | null;
  question: string | null;
  nodeId: string | null;
  nodeLabel: string | null;
  answerPath: string | null;
  answerSha256: string | null;
  taskVersion: number;
  createdAt: string | null;
  decidedAt: string | null;
}

/** 人工接手记录（执行视图投影）；旧测试或审批是否失效以服务端快照为准。 */
export interface HandoverView {
  id: string;
  status: string;
  baselineFingerprint: string | null;
  currentFingerprint: string | null;
  evidenceInvalidated: boolean;
  revalidationRequired: boolean;
  note: string | null;
  takenAt: string;
  returnedAt: string | null;
}

/**
 * 人工接手写接口返回记录（`POST /api/runs/{id}/handover` 与 `/handover/return`）。
 *
 * <p>来源：`RunHandoverService.Handover` record；与执行视图 {@link HandoverView} 不同，
 * 这里携带 `runId` / `workspaceId`，不携带由视图层衍生的 `revalidationRequired`。
 */
export interface HandoverRecord {
  id: string;
  runId: string;
  workspaceId: string;
  status: string;
  baselineFingerprint: string | null;
  currentFingerprint: string | null;
  evidenceInvalidated: boolean;
  note: string | null;
  takenAt: string;
  returnedAt: string | null;
}

/** 预算视图；`null` 上限表示不设限。 */
export interface BudgetView {
  maxRounds: number | null;
  usedRounds: number;
  remainingRounds: number | null;
  maxTools: number | null;
  usedTools: number;
  remainingTools: number | null;
  maxTokens: number | null;
  knownTokens: number;
  unknownTokenCalls: number;
  remainingTokens: number | null;
  maxSeconds: number | null;
  elapsedMs: number;
  remainingMs: number | null;
  /** 是否继承了父检查点的消耗。 */
  inherited: boolean;
}

/** 中断运行能否恢复的明确原因。 */
export interface RecoverySupportView {
  supported: boolean;
  reason: string;
}

/** 节点与调用计数汇总。 */
export interface CountsView {
  total: number;
  completed: number;
  reused: number;
  running: number;
  pending: number;
  failed: number;
  skipped: number;
  checks: number;
  modelCalls: number;
  toolCalls: number;
  unknownTokenCalls: number;
}

/**
 * 服务端执行视图（`GET /api/runs/{id}/execution-view`）。
 *
 * <p>来源：`ExecutionViewService.ExecutionView` record；`schemaVersion` 目前固定为 1。
 */
export interface ExecutionView extends RunExecutionSummary {
  schemaVersion: number;
  generatedAt: string;
  plan: ExecutionPlanView | null;
  nodes: NodeView[];
  edges: EdgeView[];
  focus: FocusView | null;
  blocking: BlockingView[];
  availableActions: ActionView[];
  recovery: RecoveryView | null;
  supplement: SupplementView | null;
  handover: HandoverView | null;
  counts: CountsView;
  budget: BudgetView;
  recoverySupport: RecoverySupportView | null;
  lastEventSeq: number;
  /** 调试执行模式；后端默认 `STEP_TEACHING`。 */
  executionMode: string;
  /** 断点节点标识清单；默认空数组。 */
  breakpoints: string[];
  revisionEdges: RevisionEdgeView[];
}

// ───────────────────────────── 节点详情 ─────────────────────────────

/** 依赖边。 */
export interface DependencyView {
  nodeId: string;
  label: string | null;
  role: string | null;
  status: string | null;
  kind: string | null;
}

/** 单次模型调用的玻璃盒载荷；内部结构未逐个核对，保留 unknown。 */
export interface ModelCallView {
  id: string;
  name: string | null;
  status: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  durationMs: number | null;
  createdAt: string | null;
  updatedAt: string | null;
  errorMessage: string | null;
  payload?: unknown;
}

/** 单次工具调用的玻璃盒追踪；内部结构未逐个核对，保留 unknown。 */
export interface ToolCallView {
  id: string;
  name: string;
  version: number | null;
  status: string | null;
  durationMs: number | null;
  createdAt: string | null;
  updatedAt: string | null;
  result: string | null;
  errorMessage: string | null;
  resourceVersion: string | null;
  trace?: unknown;
}

/** 节点的一次尝试。 */
export interface AttemptView {
  id: string;
  number: number;
  agentId: string | null;
  status: string | null;
  startedAt: string | null;
  updatedAt: string | null;
  stopCause: string | null;
  models: ModelCallView[];
  tools: ToolCallView[];
  result: string | null;
  /** `AgentAssemblySnapshot`；装配面板从这里读组件与 MCP 准入，本层保留 unknown。 */
  assembly?: unknown;
}

/** 检查输入来源。 */
export interface CheckInputView {
  dependencyNodeId: string | null;
  sourceKind: string | null;
  sourceAttemptId: string | null;
  sourceCheckId: string | null;
  sourceRunId: string | null;
  resultSha256: string | null;
  sourceResult: string | null;
}

/** 一次检查结论。 */
export interface CheckView {
  id: string;
  attemptNo: number;
  checkId: string;
  checkVersion: number;
  status: string;
  summary: string | null;
  evidence: string | null;
  externalEvidenceId: string | null;
  evidenceCurrent: boolean | null;
  inputsSha256: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  inputs: CheckInputView[];
}

/** 检查回执。 */
export interface CheckReceiptView {
  checkId: string;
  dependencyNodeId: string | null;
  dependencyNodeLabel: string | null;
  receiverNodeId: string | null;
  receiverNodeLabel: string | null;
  receiverAttemptId: string | null;
  acceptedAt: string | null;
  summary: string | null;
  evidence: string | null;
}

/** 交接时的工具证据。 */
export interface ToolEvidenceView {
  toolCallId: string;
  tool: string;
  version: number;
  resourceVersion: string | null;
}

/** 一次节点交接（入向或出向）。 */
export interface HandoffView {
  version: number;
  senderNodeId: string | null;
  senderNodeLabel: string | null;
  senderAttemptId: string | null;
  senderAgentId: string | null;
  receiverNodeId: string | null;
  receiverNodeLabel: string | null;
  receiverAttemptId: string | null;
  receiverAgentId: string | null;
  dependencyNodeId: string | null;
  dependencyNodeLabel: string | null;
  acceptedAt: string | null;
  sourceRunId: string | null;
  sourceResult: string | null;
  toolEvidence: ToolEvidenceView[];
  /** `GlassboxModels.StructuredHandoff`；结构未逐个核对，保留 unknown。 */
  structured?: unknown;
}

/** 节点事件。 */
export interface NodeEventView {
  id: number;
  type: string;
  message: string | null;
  occurredAt: string | null;
  attemptId: string | null;
  checkId: string | null;
  modelCallId: string | null;
  toolCallId: string | null;
  reasonCode: string | null;
}

/**
 * 节点详情（`GET /api/runs/{id}/execution-view/nodes/{nodeId}`）。
 *
 * <p>来源：`ExecutionViewService.NodeDetail` record。
 */
export interface NodeDetail {
  schemaVersion: number;
  generatedAt: string;
  runId: string;
  node: NodeView;
  dependencies: DependencyView[];
  attempts: AttemptView[];
  checks: CheckView[];
  inboundHandoffs: HandoffView[];
  outboundHandoffs: HandoffView[];
  checkReceipts: CheckReceiptView[];
  nodeEvents: NodeEventView[];
  result: string | null;
  /** 工具权限漏斗；缺装配证据时为 null（历史运行）。 */
  toolFunnel: ToolFunnelView | null;
}

// ───────────────────────────── 工具权限漏斗 ─────────────────────────────

/**
 * 单个外部 MCP 工具的准入结论。
 *
 * <p>来源：`ExecutionViewService.McpToolDecisionView` + `ToolFunnelProjector`。四个字段都由后端归一成
 * 空字符串而不是 null，所以这里是非空字符串。
 */
export interface McpToolDecision {
  exposedName: string;
  /** 风险等级枚举名（如 `LOW` / `HIGH`）。 */
  riskLevel: string;
  allowed: boolean;
  /** 稳定原因编码；允许时为空字符串。 */
  reasonCode: string;
}

/** 已授予的工具：真实 `ToolKind` 枚举名 + 版本号。 */
export interface ToolGrantView {
  kind: string;
  version: number | null;
}

/** 被平台裁剪掉的工具及原因编码（`NOT_DECLARED_BY_DEFINITION` 等）。 */
export interface ToolFilterView {
  kind: string;
  reason: string;
}

/**
 * 节点工具权限漏斗。
 *
 * <p>来源：`ExecutionViewService.ToolFunnelView`。`granted` 是装配时真正授予的工具，`filtered` 是
 * 被裁剪掉的真实工具与原因编码，`mcpDecisions` 精确到单个外部工具。
 *
 * <p>`attemptNumber` 与 `mcpDecisions` 都可能是旧投影缺省的结果：前者为 null，后者为空数组。
 */
export interface ToolFunnelView {
  definitionId: string | null;
  definitionVersion: number | null;
  granted: ToolGrantView[];
  filtered: ToolFilterView[];
  attemptNumber: number | null;
  mcpDecisions: McpToolDecision[];
}

// ───────────────────────────── 上下文压缩 ─────────────────────────────

/** 单条 L1 卸载记录。 */
export interface ContextOffloadDetail {
  toolCallId: string;
  /** 落库产物引用，可用 `/api/context-artifacts/{ref}` 回读原文。 */
  artifactRef: string;
  originalChars: number;
  retainedChars: number;
}

/**
 * 结构化压缩明细：逐条说明「到底删/改了什么」。
 *
 * <p>`mergedFrom` / `mergedTo` 在未发生 L2 摘要时为 -1。
 */
export interface ContextCompactionDetail {
  offloads: ContextOffloadDetail[];
  mergedFrom: number;
  mergedTo: number;
  mergedMessageCount: number;
  protectedToolCallIds: string[];
}

/**
 * 单次模型调用的上下文预算快照。
 *
 * <p>来源：`framework/context/ContextBreakdown` record。`segmentTokens` 使用固定分段名
 * （`SYSTEM`、`SKILLS`、`MEMORY`、`HANDOFF`、`TOOL_OUTPUTS`、`HISTORY`）。快照不含密钥。
 *
 * <p>`offloadedToolCallIds` / `summaryPreview` 等来自只记录总量的历史快照时可能为空值，
 * 后端已把它们归一成空数组/空字符串；`detail` 亦然（`CompactionDetail.EMPTY`）。
 */
export interface ContextBreakdown {
  modelContextLimit: number;
  /** 压缩前估算 token；历史快照可能等于 `totalEstimatedTokens`。 */
  beforeTokens: number;
  totalEstimatedTokens: number;
  segmentTokens: Record<string, number>;
  appliedTier: CompactionTier;
  savedTokens: number;
  /** 已四舍五入的节省百分比。 */
  savedPercent: number;
  offloadedToolCallIds: string[];
  summaryPreview: string;
  artifactRefs: string[];
  retainedExcerptPreview: string;
  structuredAnchors: string;
  detail: ContextCompactionDetail;
}

/**
 * 上下文产物回读结果（`GET /api/context-artifacts/{reference}`）。
 *
 * <p>来源：`ContextArtifactService.read(reference)` 的 Map 投影。`outlineJson` 是 JSON 字符串
 * （没有结构时后端给 `"[]"`），不是已解析对象。
 */
export interface ContextArtifact {
  id: string;
  title: string | null;
  charCount: number | null;
  content: string | null;
  outlineJson: string;
  createdAt: string | null;
}

// ───────────────────────────── 评测 A/B 对照 ─────────────────────────────

/** 被比较批次的一侧。 */
export interface EvalBatchRef {
  id: string;
  status: string;
  /** 批次来源种类（如 `HUB`）。 */
  sourceKind: string;
  /** 来源键；后端把 null 归一成空字符串。 */
  sourceKey: string;
  createdAt: string;
}

/**
 * 配置差异行。
 *
 * <p>`attributionStatus` 是稳定三态：`ATTRIBUTABLE` / `NOT_APPLIED` / `INSUFFICIENT_EVIDENCE`；
 * `note` 在可归因时为空字符串。`*Applied` 只在字段是消融开关时才有值，`*Count` / `*TotalCases`
 * 用于展示生效覆盖度。
 */
export interface EvalConfigDiffRow {
  field: string;
  baseline: unknown;
  candidate: unknown;
  baselineApplied: boolean | null;
  candidateApplied: boolean | null;
  baselineAppliedCount: number;
  baselineTotalCases: number;
  candidateAppliedCount: number;
  candidateTotalCases: number;
  attributionStatus: string;
  attributable: boolean;
  note: string;
}

/**
 * 分类维度的分数对照。
 *
 * <p>`delta` 只在两个批次可比时下发（不可比时该键缺失），因此是可选属性。
 */
export interface EvalCategoryComparisonRow {
  category: string;
  baselineScore: number;
  candidateScore: number;
  delta?: number;
  baselinePassRate: number;
  candidatePassRate: number;
  baselineSampleCount: number;
  candidateSampleCount: number;
}

/** 单指标 Δ 行；两侧都可能取不到值（如成本未知），此时 `delta` 为 null。 */
export interface EvalMetricDeltaRow {
  metric: string;
  baseline: number | null;
  candidate: number | null;
  delta: number | null;
}

/** 逐题对照行；分数是 0~100 的百分制。 */
export interface EvalCaseDiffRow {
  key: string;
  category: string;
  sampleId: string;
  titleZh: string;
  titleEn: string;
  baselineScore: number;
  candidateScore: number;
  baselinePassed: boolean;
  candidatePassed: boolean;
  /** `IMPROVED` / `REGRESSED` / `UNCHANGED`；一侧缺题时按 `UNCHANGED` 处理。 */
  status: string;
}

/** 裁判成本对照；`enabled` 表示候选批次是否配置了裁判模型连接。 */
export interface EvalJudgeDiff {
  baselineCostUsd: number;
  candidateCostUsd: number;
  enabled: boolean;
}

/**
 * 评测 A/B 对照结果。
 *
 * <p>来源：`evaluation/EvalCompareService.compare(baselineId, candidateId)`（`evalLabApi.compare`）。
 * 两个批次不可比时 `metricDeltas` 与 `caseDiff` 由后端下发空数组，`categoryComparison[].delta` 缺失。
 */
export interface EvalCompareResult {
  baseline: EvalBatchRef;
  candidate: EvalBatchRef;
  comparable: boolean;
  configDiff: EvalConfigDiffRow[];
  fullyAttributable: boolean;
  attributionWarnings: string[];
  categoryComparison: EvalCategoryComparisonRow[];
  metricDeltas: EvalMetricDeltaRow[];
  caseDiff: EvalCaseDiffRow[];
  judge: EvalJudgeDiff;
}

// ───────────────────────────── 事件、时间轴、检查点、对照 ─────────────────────────────

/**
 * 数据库实际发生的运行事件。
 *
 * <p>来源：`framework/event/RunEvent` record。结构化引用均可空，旧事件缺失关联时为 null；
 * 读取方不得据消息文本补造节点归属（迁移前 JS 的 `absorbEventPage` 正是照此映射）。
 */
export interface RunEvent {
  /** 全局自增 ID，仅供对账，不作为游标。 */
  id: number;
  /** 运行内提交序号，增量续读用它。 */
  runSeq: number;
  runId: string;
  type: string;
  message: string | null;
  occurredAt: string | null;
  planVersionId: string | null;
  nodeId: string | null;
  attemptId: string | null;
  agentId: string | null;
  checkId: string | null;
  modelCallId: string | null;
  toolCallId: string | null;
  reasonCode: string | null;
}

/** 事件增量页（`GET /api/runs/{id}/events`）：只回传 `nextCursor`，不得假设编号连续。 */
export interface RunEventPage {
  events: RunEvent[];
  nextCursor: number;
}

/** 时间轴汇总；对应后端 `RunObservationService.RunSummary`。 */
export interface RunTimelineSummary {
  status: string;
  elapsedMs: number;
  rounds: number;
  tools: number;
  modelCalls: number;
  toolCalls: number;
  inputTokens: number;
  outputTokens: number;
  completedNodes: number;
  totalNodes: number;
}

/** 时间轴上的一个 Span；缺起止时间时落成零宽标记。 */
export interface TimelineSpan {
  id: string;
  /** `NODE` / `ATTEMPT` / `MODEL_CALL` / `TOOL_CALL` 等。 */
  kind: string;
  nodeId: string | null;
  nodeKey: string | null;
  label: string | null;
  status: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  startMs: number | null;
  endMs: number | null;
  metrics: JsonObject;
}

/** 时间轴（`GET /api/runs/{id}/timeline`）。 */
export interface RunTimeline {
  schemaVersion: number;
  runId: string;
  summary: RunTimelineSummary;
  spans: TimelineSpan[];
  limitations: string[];
}

/** 检查点冻结状态。 */
export interface CheckpointFrozen {
  completedNodes: number;
  totalNodes: number;
  completedNodeKeys: string[];
  handoffs: number;
  modelCalls: number;
  inputTokens: number;
  outputTokens: number;
}

/** 一个检查点。 */
export interface RunCheckpoint {
  id: string;
  stage: string;
  createdAt: string;
  version: number | null;
  rounds: number;
  tools: number;
  elapsedMs: number;
  engine: string;
  checksum: string;
  eventId: number | null;
  frozen: CheckpointFrozen;
}

/** 检查点列表（`GET /api/runs/{id}/checkpoints`）。 */
export interface RunCheckpointList {
  schemaVersion: number;
  runId: string;
  checkpoints: RunCheckpoint[];
}

/** 对照里单侧节点的摘要。 */
export interface RunDiffNodeSide {
  status: string;
  elapsedMs: number | null;
  output: string | null;
  modelCalls: number;
  toolCalls: number;
  files: string[];
}

/** 逐节点对照行；仅在某一侧存在的分叉节点另一侧返回 `null`。 */
export interface RunDiffNode {
  key: string;
  title: string | null;
  role: string | null;
  base: RunDiffNodeSide | null;
  target: RunDiffNodeSide | null;
}

/** 运行指标块（对照两侧各一份）。 */
export interface RunDiffMetrics {
  status: string;
  elapsedMs: number;
  rounds: number;
  tools: number;
  modelCalls: number;
  toolCalls: number;
  inputTokens: number;
  outputTokens: number;
  completedNodes: number;
  totalNodes: number;
}

/** 两次运行的结构化对照（`GET /api/runs/{id}/diff/{otherId}`）。 */
export interface RunDiff {
  schemaVersion: number;
  baseRunId: string;
  targetRunId: string;
  base: RunDiffMetrics;
  target: RunDiffMetrics;
  nodes: RunDiffNode[];
  onlyInBase: string[];
  onlyInTarget: string[];
  limitations: string[];
}

// ───────────────────────────── 交付、分支、审批、计划候选 ─────────────────────────────

/** 交付文件行；`path` / `sha256` 是回读正文时的校验依据。 */
export interface DeliveryFile {
  path: string;
  sha256: string;
  [key: string]: unknown;
}

/**
 * 运行级交付说明。
 *
 * <p>来源：`RunDeliveryService.delivery(id)`。`nodes[].files` 是写入节点下的文件分组，
 * 内部行结构未逐个核对，保留 unknown。
 */
export interface RunDelivery {
  files: DeliveryFile[];
  nodes: JsonObject[];
  [key: string]: unknown;
}

/**
 * 交付正文回读结果。
 *
 * <p>`currentMatches` 与 `sha256` 至少有一个可用：前端用
 * `payload.currentMatches ?? payload.sha256 === file.sha256` 判定正文是否仍然新鲜。
 */
export interface RunDeliveryFile {
  path: string;
  sha256: string;
  currentMatches?: boolean;
  content?: string;
  [key: string]: unknown;
}

/** 分支运行来源视图（只读投影，不含被覆盖后的正文）。 */
export interface RunForkSource {
  runId: string;
  parentRunId: string | null;
  sourceNodeKey: string | null;
  checkpointId: string | null;
  promptAddendum: string | null;
  modelMode: string | null;
  modelConnectionId: string | null;
  createdAt: string;
}

/** 某条运行的全部分支（`GET /api/runs/{id}/forks`）。 */
export interface RunForkList {
  runId: string;
  forks: RunForkSource[];
}

/** 分叉结果。 */
export interface RunForked {
  runId: string;
  taskId: string;
  workspaceId: string | null;
  parentRunId: string | null;
}

/** 工具审批请求（`ToolApprovalService.ApprovalView`）。 */
export interface ToolApproval {
  id: string;
  runId: string;
  nodeId: string | null;
  agentId: string | null;
  toolName: string;
  toolVersion: number;
  originalArgsJson: string;
  editedArgsJson: string | null;
  /** `LOW` / `HIGH` 等枚举名。 */
  riskLevel: string;
  riskReason: string | null;
  status: string;
  decisionAction: string | null;
  decisionReason: string | null;
  requestedAt: string | null;
  decidedAt: string | null;
  expiresAt: string | null;
}

/** 计划候选里的一处字段差异。 */
export interface PlanProposalChange {
  key: string;
  label: string;
  beforeMaxModelCalls: number | null;
  afterMaxModelCalls: number | null;
  beforeMaxToolCalls: number | null;
  afterMaxToolCalls: number | null;
  addedDependencies: string[];
  removedDependencies: string[];
}

/** 计划候选（`POST /api/runs/{runId}/plan-proposals`）。 */
export interface PlanProposal {
  id: string;
  runId: string;
  planVersionId: string;
  version: number;
  baseSha256: string;
  proposalSha256: string;
  status: string;
  createdAt: string;
  diff: PlanProposalChange[];
}

/** 应用候选后的结果。 */
export interface PlanProposalApplied {
  adjustmentId: string;
  graphSha256: string;
  diff: PlanProposalChange[];
}

/** 迭代一轮的结果（`RunIterationService.Iterated`）。 */
export interface RunIterationResult {
  runId: string;
  taskId: string;
  roundIndex: number;
  parentRunId: string;
}

/** 恢复子运行的结果（`WorkbenchRunController.recover`）。 */
export interface RunRecoverResult {
  runId: string;
}

/** 提交补充信息的结果（`WorkbenchRunController.submitSupplement`）。 */
export interface SupplementSubmitResult {
  accepted: boolean;
  taskVersion: number;
  runStatus: string;
}

/** 只回执「已受理」的写操作（暂停/继续/取消/确认计划）。 */
export interface AcceptedResponse {
  accepted: boolean;
}

// ───────────────────────────── 运行统计与架构透视 ─────────────────────────────

/** 单实例的同名工具调用汇总；`knownDurationMs` 为空表示没有可统计的耗时。 */
export interface ToolMetricsRow {
  name: string;
  calls: number;
  statusCounts: Record<string, number>;
  unknownStatusCount: number;
  knownDurationMs: number | null;
  unknownDurationCount: number;
}

/**
 * 单个 Agent 的调用统计（`GET /api/runs/{id}/agent-metrics`）。
 *
 * <p>来源：`observability/AgentMetrics` record。时长为包含等待与暂停的墙钟区间；未知值用 null
 * 或 `unknown*Count` 显式计数，而不是当成 0。
 */
export interface AgentMetricsRow {
  agentId: string;
  roleName: string | null;
  durationMs: number | null;
  modelCalls: number;
  modelDurationKnownMs: number;
  modelDurationUnknownCount: number;
  knownInputTokens: number | null;
  unknownInputCount: number;
  knownOutputTokens: number | null;
  unknownOutputCount: number;
  toolCalls: number;
  toolDurationKnownMs: number;
  toolDurationUnknownCount: number;
  toolNames: string[];
  toolBreakdown: ToolMetricsRow[];
}

/** 架构透视的模块行。 */
export interface ArchitectureXrayModule {
  id: string;
  status: string;
  callCount: number;
  durationMs: number;
  compactionSavedPercent: number | null;
  gateBlocks: number;
  anchorNodes: string[];
}

/** 架构透视的时间线步骤。 */
export interface ArchitectureXrayStep {
  id: string;
  atMs: number;
  kind: string;
  label: string;
  moduleId: string;
  nodeId: string | null;
  glassboxTab: string | null;
}

/** 架构透视汇总。 */
export interface ArchitectureXraySummary {
  status: string;
  activeModules: number;
  modelCalls: number;
  toolCalls: number;
  handoffs: number;
  checkpoints: number;
  inputTokens: number;
  outputTokens: number;
}

/** 架构透视（`GET /api/runs/{id}/architecture-xray`）。 */
export interface ArchitectureXray {
  schemaVersion: number;
  runId: string;
  summary: ArchitectureXraySummary;
  modules: ArchitectureXrayModule[];
  steps: ArchitectureXrayStep[];
  limitations: string[];
}
